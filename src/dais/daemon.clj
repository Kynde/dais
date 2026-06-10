(ns dais.daemon
  "dais coordinator: ndjson over a Unix domain socket.

  Protocol (one JSON request per line, one JSON response per line):
    {\"op\":\"publish\",\"event\":{...}}                 transcript -> route -> execute
    {\"op\":\"control\",\"action\":\"toggle-vad\"}        also: toggle-record, voice-off,
                                                      arm, esc, shutdown
    {\"op\":\"target\",\"action\":\"set\",\"slot\":1,
     \"pane\":\"app:1.2\"|\"focus\"|\"current\"}          also: use, list
    {\"op\":\"query\",\"query\":\"status\"}
    {\"op\":\"events\",\"last\":20}
    {\"op\":\"replay\",\"trace_id\":\"...\"}

  Socket plumbing adapted from lausu.daemon. This is the only namespace doing
  socket I/O; routing and transitions are pure (dais.router, dais.state).

  Milestone 2 adds ear-worker supervision (capture/VAD/ASR subprocess); the
  mode transitions already model it."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dais.config :as config]
            [dais.ear :as ear]
            [dais.event :as event]
            [dais.executor :as executor]
            [dais.log :as log]
            [dais.notify :as notify]
            [dais.router :as router]
            [dais.state :as state])
  (:import (java.net StandardProtocolFamily UnixDomainSocketAddress)
           (java.nio.channels Channels ServerSocketChannel SocketChannel)
           (java.nio.file Files Path)
           (java.io BufferedReader InputStreamReader OutputStreamWriter)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent.atomic AtomicLong))
  (:gen-class))

(defonce ^:private sequence-counter (AtomicLong. 0))
(def ^:private handler-lock (Object.))

(defn- with-sequence [event]
  (assoc event "sequence" (.incrementAndGet sequence-counter)))

(defn- daemon-event [type payload {:keys [trace-id parent-id]}]
  (with-sequence
    (event/make-event {:type type
                       :source {"module" "dais-daemon"}
                       :trace-id trace-id
                       :parent-id parent-id
                       :payload payload})))

(defn- mode-label [st]
  (case (:mode st)
    :off "voice off"
    :vad-listening "VAD listening"
    :manual-recording "recording"))

(defn- target-label [st]
  (let [t (state/active-target st)]
    (cond (nil? t) "no target"
          (= :focus (:type t)) "focus"
          :else (:pane t))))

(defn- target->json [t]
  (when t
    (cond-> {"type" (name (:type t))}
      (:pane t) (assoc "pane" (:pane t)))))

(defn- write-state! [{:keys [runtime-dir state]}]
  (state/write-files! runtime-dir @state))

(defn- transition!
  "Apply a pure transition fn to the state atom. On success: persist state
  files, sync the ear worker (latch/mode control messages), notify, and
  return {\"ok\" true \"event\" <control.state_changed>}. On refusal: notify
  the error and return {\"ok\" false ...}. Callers hold handler-lock, so
  read-transition-write is not racy."
  [{:keys [state runtime-dir config] :as ctx} f via ids]
  (let [old-mode (:mode @state)
        res (f @state)]
    (if-let [err (:error res)]
      (do (notify/notify! config "dais" err)
          {"ok" false "error" err})
      (let [new-state (:state res)]
        (reset! state new-state)
        (state/write-files! runtime-dir new-state)
        (when-let [msg (ear/ear-message old-mode (:mode new-state) via)]
          (when-let [e (some-> (:ear ctx) deref)]
            (ear/send! e msg)))
        (notify/notify! config "dais" (str (mode-label new-state)
                                           " · target " (target-label new-state)))
        {"ok" true
         "event" (daemon-event "control.state_changed"
                               {"via" via
                                "mode" (name (:mode new-state))
                                "active_slot" (:active-slot new-state)
                                "target" (target-label new-state)}
                               ids)}))))

(defn- plan->json [plan]
  (cond-> {"action" (name (:action plan))}
    (:text plan) (assoc "text" (:text plan))
    (contains? plan :submit) (assoc "submit" (boolean (:submit plan)))
    (:keys plan) (assoc "keys" (vec (:keys plan)))
    (:control plan) (assoc "control" (name (:control plan)))
    (:slot plan) (assoc "slot" (:slot plan))
    (:reason plan) (assoc "reason" (:reason plan))))

(defn- execute-plan!
  "Run a :type-text/:press-keys plan via the executor against the active
  target. Returns the action.executed / action.error event."
  [{:keys [state config dry-run] :as _ctx} plan ids]
  (let [target (state/active-target @state)
        result (executor/execute plan target config {:dry-run dry-run})
        ok? (= "ok" (get result "result"))]
    (when-not ok?
      (notify/notify! config "dais error" (get result "error")))
    (daemon-event (if ok? "action.executed" "action.error")
                  (assoc result "plan" (plan->json plan))
                  ids)))

(defn- control-transition [control slot]
  (case control
    :voice-off state/voice-off
    :next-target state/next-slot
    :set-slot #(state/set-slot % slot)
    nil))

(defn- dispatch-plan!
  "Turn a router plan into executed effects. Returns the events to log."
  [ctx plan ids]
  (case (:action plan)
    :none [(daemon-event "action.error"
                         {"stage" "router" "dropped" true
                          "error" (:reason plan)
                          "plan" (plan->json plan)}
                         ids)]
    :control (let [f (control-transition (:control plan) (:slot plan))
                   res (transition! ctx f (str "voice:" (name (:control plan))) ids)]
               (if (get res "ok")
                 [(get res "event")]
                 [(daemon-event "action.error"
                                {"stage" "control" "error" (get res "error")
                                 "plan" (plan->json plan)}
                                ids)]))
    [(execute-plan! ctx plan ids)]))

(defn- route-opts [{:keys [config commands state]}]
  {:strategy (get-in config [:router :strategy] :whole-match)
   :prefix (get-in config [:router :prefix] "do")
   :commands commands
   :enter-mode (:enter-mode config :no-enter)
   :armed (:armed @state)})

(defn- handle-publish
  [{:keys [state events-dir] :as ctx} {:strs [event]}]
  (cond
    (not (event/valid-envelope? event))
    {"ok" false "error" "Invalid event envelope (missing/invalid required fields)."}

    (= "voice.transcript" (get event "type"))
    (let [trace-id (or (get event "trace_id") (get event "id"))
          transcript (-> event (assoc "trace_id" trace-id) with-sequence)
          text (get-in event ["payload" "text"])
          plan (router/route text (route-opts ctx))
          ids {:trace-id trace-id :parent-id (get transcript "id")}]
      ;; Armed is single-shot: consumed by this utterance, hit or miss.
      (swap! state assoc :armed false :last-utterance text)
      (let [effects (dispatch-plan! ctx plan ids)
            all (into [transcript] effects)]
        (write-state! ctx)
        (log/append-all! events-dir all)
        {"ok" true "trace_id" trace-id "plan" (plan->json plan) "events" all}))

    :else
    ;; Non-transcript events (ear lifecycle etc.) are logged as-is.
    (let [stamped (with-sequence event)]
      (log/append! events-dir stamped)
      {"ok" true "events" [stamped]})))

(defn- handle-control
  [{:keys [events-dir] :as ctx} {:strs [action]}]
  (case action
    ("toggle-vad" "toggle-record" "voice-off" "arm")
    (let [f (case action
              "toggle-vad" state/toggle-vad
              "toggle-record" state/toggle-record
              "voice-off" state/voice-off
              "arm" state/arm)
          res (transition! ctx f action nil)]
      (when-let [ev (get res "event")]
        (log/append! events-dir ev))
      (dissoc res "event"))

    "esc"
    (let [plan {:action :press-keys :keys ["Escape"]}
          ev (execute-plan! ctx plan nil)]
      (log/append! events-dir ev)
      {"ok" (= "action.executed" (get ev "type")) "events" [ev]})

    "shutdown"
    (do (future (Thread/sleep 200) (System/exit 0))
        {"ok" true "status" "shutting-down"})

    {"ok" false "error" (str "Unknown control action: " (pr-str action))}))

(defn- parse-target-value
  "\"focus\" -> focus target; \"current\" -> the active tmux pane;
  \"session:window.pane\" -> tmux target. Returns {:target t} or {:error e}."
  [config value]
  (cond
    (= "focus" value) {:target {:type :focus}}
    (= "current" value) (if-let [pane (executor/current-pane config)]
                          {:target {:type :tmux :pane pane}}
                          {:error "Could not resolve current tmux pane (is tmux running?)"})
    (and (string? value) (re-matches #"[^\s:]+:[^\s.]+\.\S+" value))
    {:target {:type :tmux :pane value}}
    :else {:error (str "Bad target " (pr-str value)
                       " (expected session:window.pane, focus, or current)")}))

(defn- handle-target
  [{:keys [state events-dir config] :as ctx} {:strs [action slot pane]}]
  (case action
    "set"
    (if-not (integer? slot)
      {"ok" false "error" "target set needs an integer slot"}
      (let [{:keys [target error]} (parse-target-value config pane)]
        (if error
          {"ok" false "error" error}
          (let [res (transition! ctx #(state/set-target % slot target)
                                 (str "target-set:" slot) nil)]
            (when-let [ev (get res "event")]
              (log/append! events-dir ev))
            (-> res (dissoc "event") (assoc "slot" slot "target" (target->json target)))))))

    "use"
    (if-not (integer? slot)
      {"ok" false "error" "target use needs an integer slot"}
      (let [res (transition! ctx #(state/set-slot % slot) (str "target-use:" slot) nil)]
        (when-let [ev (get res "event")]
          (log/append! events-dir ev))
        (dissoc res "event")))

    "list"
    (let [st @state]
      {"ok" true
       "active_slot" (:active-slot st)
       "targets" (into {} (map (fn [[k v]] [(str k) (target->json v)])
                               (:targets st)))})

    {"ok" false "error" (str "Unknown target action: " (pr-str action))}))

(defn handle-request
  "Public for tests. Serialized: requests are quick and state transitions
  must not interleave."
  [{:keys [events-dir dry-run state] :as ctx} req]
  (locking handler-lock
    (case (get req "op")
      "publish" (handle-publish ctx req)
      "control" (handle-control ctx req)
      "target" (handle-target ctx req)
      "query" (case (get req "query")
                "status" (let [st @state]
                           {"ok" true "status" "running"
                            "execution" (if dry-run "dry-run" "live")
                            "mode" (name (:mode st))
                            "armed" (boolean (:armed st))
                            "active_slot" (:active-slot st)
                            "target" (target-label st)
                            "sequence" (.get sequence-counter)
                            "events_dir" events-dir})
                {"ok" false "error" (str "Unknown query: " (pr-str (get req "query")))})
      "events" {"ok" true "events" (log/last-n events-dir (or (get req "last") 20))}
      "replay" {"ok" true "events" (log/by-trace events-dir (get req "trace_id"))}
      {"ok" false "error" (str "Unknown op: " (pr-str (get req "op")))})))

(defn make-ctx
  "Assemble the daemon context. Public for tests."
  [{:keys [config events-dir runtime-dir dry-run]}]
  {:config config
   :commands (router/merged-commands (get-in config [:router :commands]))
   :state (atom (state/initial-state config))
   :ear (atom nil)
   :events-dir events-dir
   :runtime-dir runtime-dir
   :dry-run (boolean dry-run)})

(defn- on-ear-event
  "Handle one event from the ear worker's stdout. Transcripts run the full
  publish path (route -> execute -> log); lifecycle events update state and
  are logged."
  [{:keys [events-dir config state] :as ctx} ev]
  (let [t (get ev "type")]
    (case t
      "voice.transcript" (handle-request ctx {"op" "publish" "event" ev})
      ("asr.speech_start" "asr.speech_end")
      (locking handler-lock
        (swap! state assoc :speech (= t "asr.speech_start"))
        (write-state! ctx)
        (log/append! events-dir (with-sequence ev)))
      ("asr.ready" "asr.listening" "asr.error")
      (locking handler-lock
        (log/append! events-dir (with-sequence ev))
        (case t
          "asr.ready" (notify/notify! config "dais" "ear ready (model loaded)")
          "asr.error" (notify/notify! config "dais error" (get-in ev ["payload" "error"]))
          nil))
      ;; Unknown types are logged if they carry a valid envelope, else dropped.
      (when (event/valid-envelope? ev)
        (locking handler-lock
          (log/append! events-dir (with-sequence ev)))))))

(defn start-ear!
  "Spawn the ear worker per config (:ear {:enabled? :cmd :dir}) and wire its
  events into ctx. No-op when disabled. Public for a REPL restart."
  [{:keys [config events-dir] :as ctx}]
  (when (get-in config [:ear :enabled?] false)
    (let [asr (:asr config)
          audio-dir (.getAbsolutePath (io/file events-dir "audio"))
          worker (ear/start!
                  {:cmd (get-in config [:ear :cmd])
                   :dir (get-in config [:ear :dir] "ear")
                   :args (cond-> ["--model" (:model asr "small.en")
                                  "--device" (:device asr "cpu")
                                  "--compute-type" (:compute-type asr "int8")
                                  "--language" (:language asr "en")
                                  "--audio-dir" audio-dir
                                  "--tuning" (json/write-str (or (:vad config) {}))]
                           (:source asr) (into ["--source" (:source asr)]))}
                  #(on-ear-event ctx %))]
      (reset! (:ear ctx) worker)
      worker)))

(defn- serve-connection
  [ctx ^SocketChannel ch]
  (with-open [ch ch
              in (BufferedReader. (InputStreamReader.
                                   (Channels/newInputStream ch) StandardCharsets/UTF_8))
              out (OutputStreamWriter. (Channels/newOutputStream ch) StandardCharsets/UTF_8)]
    (loop []
      (when-let [line (.readLine in)]
        (when-not (str/blank? line)
          (let [resp (try
                       (handle-request ctx (json/read-str line))
                       (catch Exception e
                         {"ok" false "error" (str "Request failed: " (.getMessage e))}))]
            (.write out (json/write-str resp))
            (.write out "\n")
            (.flush out)))
        (recur)))))

(defn- delete-if-exists [^String path]
  (Files/deleteIfExists (Path/of path (make-array String 0))))

(defn start
  "Start the daemon, blocking the calling thread. opts:
    :config      loaded config map (default: dais.config/load-config)
    :socket-path Unix socket path
    :events-dir  JSONL log dir
    :dry-run     when true the executor only plans, never executes"
  [{:keys [socket-path events-dir cfg dry-run]}]
  (let [cfg (or cfg (config/load-config))
        socket-path (or socket-path (config/socket-path cfg))
        events-dir (or events-dir (config/events-dir cfg))
        runtime-dir (config/runtime-dir)
        ctx (make-ctx {:config cfg :events-dir events-dir
                       :runtime-dir runtime-dir :dry-run dry-run})]
    (Files/createDirectories (.getParent (Path/of socket-path (make-array String 0)))
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (delete-if-exists socket-path)
    (write-state! ctx)
    (start-ear! ctx)
    (let [server (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
      (.bind server (UnixDomainSocketAddress/of socket-path))
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn []
                                   (try (.close server) (catch Exception _))
                                   (try (some-> (:ear ctx) deref ear/stop!) (catch Exception _))
                                   (try (delete-if-exists socket-path) (catch Exception _))
                                   (try (state/cleanup-files! runtime-dir) (catch Exception _)))))
      (println (str "dais-daemon listening on " socket-path
                    " [" (if dry-run "dry-run" "LIVE") "]"
                    " (events -> " events-dir
                    ", targets: " (str/join ", " (map (fn [[k v]] (str k "=" (or (:pane v) (name (:type v)))))
                                                      (:targets cfg)))
                    ")"))
      (loop []
        (let [ch (.accept server)]
          (.start (Thread. (fn [] (try (serve-connection ctx ch)
                                       (catch Exception e
                                         (binding [*out* *err*]
                                           (println "connection error:" (.getMessage e)))))))))
        (recur)))))

(defn -main
  "Usage: clojure -M:daemon [socket-path] [--dry-run] [--no-ear]"
  [& args]
  (let [dry-run? (boolean (some #{"--dry-run"} args))
        no-ear? (boolean (some #{"--no-ear"} args))
        positional (remove #(str/starts-with? % "--") args)
        cfg (cond-> (config/load-config)
              no-ear? (assoc-in [:ear :enabled?] false))]
    (start {:socket-path (first positional) :cfg cfg :dry-run dry-run?})))
