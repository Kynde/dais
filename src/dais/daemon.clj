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

(defn- mode-notification
  "[summary icon] for the state-change notification."
  [st]
  (case (:mode st)
    :off ["Voice off" "microphone-sensitivity-muted"]
    :vad-listening ["Listening" "audio-input-microphone"]
    :manual-recording ["Recording" "media-record"]))

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

;; --- subscribers (dais-top etc.) ---
;; Entirely optional: with no subscribers, broadcast! is a no-op and the ear
;; never emits level events (set_levels toggles on 0 <-> n transitions), so
;; the system carries no overhead when no UI is watching.

(defn- sync-levels!
  "Tell the ear whether anyone is watching the meter."
  [ctx on?]
  (when-let [e (some-> (:ear ctx) deref)]
    (ear/send! e {"type" "set_levels" "on" (boolean on?)})))

(defn subscribe!
  [ctx ^java.io.Writer w]
  (let [subs (swap! (:subscribers ctx) conj w)]
    (when (= 1 (count subs)) (sync-levels! ctx true))))

(defn unsubscribe!
  [ctx ^java.io.Writer w]
  (let [subs (swap! (:subscribers ctx) disj w)]
    (when (zero? (count subs)) (sync-levels! ctx false))))

(defn broadcast!
  "Push one event line to every subscriber; a failing writer is dropped."
  [ctx event]
  (let [subs @(:subscribers ctx)]
    (when (seq subs)
      (let [line (str (json/write-str event) "\n")]
        (doseq [^java.io.Writer w subs]
          (try
            (locking w (.write w line) (.flush w))
            (catch Exception _ (unsubscribe! ctx w))))))))

(defn- record!
  "Append to the audit log and push to subscribers."
  [ctx event]
  (log/append! (:events-dir ctx) event)
  (broadcast! ctx event)
  event)

(defn- record-all! [ctx events]
  (log/append-all! (:events-dir ctx) events)
  (run! #(broadcast! ctx %) events)
  events)

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
      (do (notify/notify! config "Dais" err {:icon "dialog-warning"})
          {"ok" false "error" err})
      (let [new-state (:state res)]
        (reset! state new-state)
        (state/write-files! runtime-dir new-state)
        (when-let [msg (ear/ear-message old-mode (:mode new-state) via)]
          (when-let [e (some-> (:ear ctx) deref)]
            (ear/send! e msg)))
        (let [[label icon] (mode-notification new-state)]
          (notify/notify! config label
                          (str "Target: " (target-label new-state))
                          {:icon icon :urgency "low"}))
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
      (notify/notify! config "Dais error" (get result "error")
                      {:icon "dialog-error"}))
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
  [{:keys [state] :as ctx} {:strs [event]}]
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
        (record-all! ctx all)
        {"ok" true "trace_id" trace-id "plan" (plan->json plan) "events" all}))

    :else
    ;; Non-transcript events (ear lifecycle etc.) are logged as-is.
    (let [stamped (with-sequence event)]
      (record! ctx stamped)
      {"ok" true "events" [stamped]})))

(defn- handle-control
  [ctx {:strs [action]}]
  (case action
    ("toggle-vad" "toggle-record" "voice-off" "arm")
    (let [f (case action
              "toggle-vad" state/toggle-vad
              "toggle-record" state/toggle-record
              "voice-off" state/voice-off
              "arm" state/arm)
          res (transition! ctx f action nil)]
      (when-let [ev (get res "event")]
        (record! ctx ev))
      (dissoc res "event"))

    "esc"
    (let [plan {:action :press-keys :keys ["Escape"]}
          ev (execute-plan! ctx plan nil)]
      (record! ctx ev)
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
  [{:keys [state config] :as ctx} {:strs [action slot pane]}]
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
              (record! ctx ev))
            (-> res (dissoc "event") (assoc "slot" slot "target" (target->json target)))))))

    "use"
    (if-not (integer? slot)
      {"ok" false "error" "target use needs an integer slot"}
      (let [res (transition! ctx #(state/set-slot % slot) (str "target-use:" slot) nil)]
        (when-let [ev (get res "event")]
          (record! ctx ev))
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
                "status" (let [st @state
                               config (:config ctx)]
                           {"ok" true "status" "running"
                            "execution" (if dry-run "dry-run" "live")
                            "mode" (name (:mode st))
                            "armed" (boolean (:armed st))
                            "active_slot" (:active-slot st)
                            "target" (target-label st)
                            "ear_alive" (boolean (some-> (:ear ctx) deref ear/alive?))
                            "strategy" (name (get-in config [:router :strategy] :whole-match))
                            "enter_mode" (name (:enter-mode config :no-enter))
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
   :subscribers (atom #{})
   :events-dir events-dir
   :runtime-dir runtime-dir
   :dry-run (boolean dry-run)})

(defn- on-ear-event
  "Handle one event from the ear worker's stdout. Transcripts run the full
  publish path (route -> execute -> log); lifecycle events update state and
  are logged."
  [{:keys [config state] :as ctx} ev]
  (let [t (get ev "type")]
    (case t
      ;; Meter events: ephemeral by design — broadcast to subscribers only,
      ;; never logged, no sequence (would flood the audit log at ~8 Hz).
      "asr.level" (broadcast! ctx ev)
      "voice.transcript" (handle-request ctx {"op" "publish" "event" ev})
      ("asr.speech_start" "asr.speech_end")
      (locking handler-lock
        (swap! state assoc :speech (= t "asr.speech_start"))
        (write-state! ctx)
        (record! ctx (with-sequence ev)))
      ("asr.ready" "asr.listening" "asr.error")
      (locking handler-lock
        (record! ctx (with-sequence ev))
        (case t
          ;; A freshly (re)started ear defaults to levels-off; if a UI is
          ;; already subscribed, switch the meter back on.
          "asr.ready" (do (when (seq @(:subscribers ctx))
                            (sync-levels! ctx true))
                          (notify/notify! config "Dais ready" "Speech model loaded"
                                          {:icon "audio-input-microphone" :urgency "low"}))
          "asr.error" (notify/notify! config "Dais error" (get-in ev ["payload" "error"])
                                      {:icon "dialog-error"})
          nil))
      ;; Unknown types are logged if they carry a valid envelope, else dropped.
      (when (event/valid-envelope? ev)
        (locking handler-lock
          (record! ctx (with-sequence ev)))))))

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
  "One client connection. A {\"op\":\"subscribe\"} request turns it into a push
  stream: the writer joins the subscriber registry (acked once) and every
  recorded event — plus ephemeral asr.level events — is pushed as ndjson until
  the client disconnects. Response writes lock the writer because broadcasts
  may interleave from other threads."
  [ctx ^SocketChannel ch]
  (let [out (OutputStreamWriter. (Channels/newOutputStream ch) StandardCharsets/UTF_8)]
    (try
      (with-open [ch ch
                  in (BufferedReader. (InputStreamReader.
                                       (Channels/newInputStream ch) StandardCharsets/UTF_8))]
        (loop []
          (when-let [line (.readLine in)]
            (when-not (str/blank? line)
              (let [resp (try
                           (let [req (json/read-str line)]
                             (if (= "subscribe" (get req "op"))
                               (do (subscribe! ctx out)
                                   {"ok" true "subscribed" true})
                               (handle-request ctx req)))
                           (catch Exception e
                             {"ok" false "error" (str "Request failed: " (.getMessage e))}))]
                (locking out
                  (.write out (json/write-str resp))
                  (.write out "\n")
                  (.flush out))))
            (recur))))
      (finally
        (unsubscribe! ctx out)))))

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
