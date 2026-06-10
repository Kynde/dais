(ns dais.executor
  "Render router plans through a delivery backend, chosen by the active
  target: :tmux (paste-buffer / send-keys to a pane) or :focus (ydotool to
  the focused app).

  Invariants (inherited from lausu's executor):
  - Subprocesses run from argv vectors, never shell strings. Dictation text
    travels on stdin (tmux load-buffer - / ydotool type --file -), so it is
    never an argv element.
  - Key names are a whitelist; free-form text can never become key input.
  - Pane existence is checked with `list-panes -t` (exits non-zero for an
    unknown target; display-message is unsuitable — it exits 0 and falls back
    to the current client).
  - Dry-run returns the full command plan under \"would_run\" without
    executing anything."
  (:require [clojure.string :as str]
            [dais.config :as config])
  (:import (java.lang ProcessBuilder$Redirect)
           (java.nio.charset StandardCharsets)
           (java.util UUID)))

(def allowed-key-names
  "The only key names a press-keys plan may deliver."
  (into #{"Enter" "Escape" "Up" "Down" "Left" "Right" "Tab" "BSpace"
          "C-c" "C-d" "C-u"}
        (map str (range 1 10))))

(def ^:private focus-keycodes
  "Key name -> Linux input keycodes for the ydotool backend. A multi-code
  entry is a chord: press in order, release in reverse."
  (merge {"Enter" [28] "Escape" [1] "Tab" [15] "BSpace" [14]
          "Up" [103] "Down" [108] "Left" [105] "Right" [106]
          "C-c" [29 46] "C-d" [29 32] "C-u" [29 22]}
         ;; KEY_1..KEY_9 are keycodes 2..10
         (into {} (map (fn [n] [(str n) [(inc n)]]) (range 1 10)))))

(defn run-proc
  "Run one argv vector. :in is written to stdin; :env entries are set on the
  subprocess environment. Returns {:exit :out :err}."
  [argv {:keys [in env]}]
  (let [pb (doto (ProcessBuilder. ^java.util.List (vec argv))
             (.redirectInput (if in
                               ProcessBuilder$Redirect/PIPE
                               ProcessBuilder$Redirect/INHERIT)))]
    (doseq [[k v] env] (.put (.environment pb) k v))
    (let [proc (.start pb)]
      (when in
        (with-open [os (.getOutputStream proc)]
          (.write os (.getBytes ^String in StandardCharsets/UTF_8))))
      (let [out (slurp (.getInputStream proc))
            err (slurp (.getErrorStream proc))]
        (.waitFor proc)
        {:exit (.exitValue proc) :out out :err err}))))

(defn- ok-result [backend details]
  {"executor" backend "result" "ok" "dry_run" false "details" details})

(defn- dry-result [backend details plan]
  {"executor" backend "result" "ok" "dry_run" true
   "details" (assoc details "would_run"
                    (mapv (fn [{:keys [argv stdin]}]
                            (cond-> {"argv" (vec argv)}
                              stdin (assoc "stdin" stdin)))
                          plan))})

(defn- err-result [backend msg]
  {"executor" backend "result" "error" "dry_run" false "error" msg})

(defn- run-plan
  "Run steps in order, stop at the first failure. Each step is
  {:argv [...] :stdin text :env {...} :label \"...\"}. Returns nil on full
  success, else an error message."
  [plan]
  (loop [[{:keys [argv stdin env label]} & more] plan]
    (when argv
      (let [{:keys [exit err]} (run-proc argv {:in stdin :env env})]
        (if (zero? exit)
          (recur more)
          (str (or label (first argv)) " failed: " (str/trim (or err ""))))))))

;; --- tmux backend ---

(defn- tmux-prefix [config] (vec (or (:tmux config) ["tmux"])))

(defn pane-exists? [config pane]
  (zero? (:exit (run-proc (into (tmux-prefix config)
                                ["list-panes" "-t" pane "-F" "#{pane_id}"])
                          {}))))

(defn current-pane
  "The active pane as session:window.pane, or nil when tmux is unreachable.
  Used by `dais-ctl target set N current`."
  [config]
  (let [{:keys [exit out]} (run-proc (into (tmux-prefix config)
                                           ["display-message" "-p"
                                            "#{session_name}:#{window_index}.#{pane_index}"])
                                     {})]
    (when (zero? exit) (not-empty (str/trim out)))))

(defn- tmux-type-plan [config pane text submit?]
  (let [prefix (tmux-prefix config)
        buffer (str "dais-" (UUID/randomUUID))]
    (cond-> []
      (seq text) (into [{:argv (into prefix ["load-buffer" "-b" buffer "-"])
                         :stdin text :label "tmux load-buffer"}
                        {:argv (into prefix ["paste-buffer" "-t" pane "-b" buffer "-d" "-p"])
                         :label "tmux paste-buffer"}])
      submit? (conj {:argv (into prefix ["send-keys" "-t" pane "Enter"])
                     :label "tmux send-keys Enter"}))))

(defn- tmux-keys-plan [config pane keys*]
  [{:argv (into (tmux-prefix config) (into ["send-keys" "-t" pane] keys*))
    :label "tmux send-keys"}])

(defn- tmux-execute [plan-steps details config pane dry-run]
  (cond
    dry-run (dry-result "tmux" details plan-steps)
    (not (pane-exists? config pane))
    (err-result "tmux" (str "Target pane not found: " pane
                            " (check targets in config/dais.edn against your tmux)"))
    :else (if-let [msg (run-plan plan-steps)]
            (err-result "tmux" msg)
            (ok-result "tmux" details))))

;; --- focus backend (ydotool) ---

(defn- ydotool-argv [config & args]
  (into (vec (get-in config [:focus :ydotool] ["/usr/bin/ydotool"])) args))

(defn- ydotool-env [config]
  {"YDOTOOL_SOCKET" (config/ydotool-socket config)})

(defn- key-args
  "ydotool key arguments for one key name: press codes in order, release in
  reverse (chords like C-c work out naturally)."
  [key-name]
  (let [codes (focus-keycodes key-name)]
    (concat (map #(str % ":1") codes)
            (map #(str % ":0") (reverse codes)))))

(defn- focus-type-plan [config text submit?]
  (let [env (ydotool-env config)
        delay (str (get-in config [:focus :key-delay-ms] 2))]
    (cond-> []
      (seq text) (conj {:argv (ydotool-argv config "type" "--key-delay" delay "--file" "-")
                        :stdin text :env env :label "ydotool type"})
      submit? (conj {:argv (apply ydotool-argv config "key" (key-args "Enter"))
                     :env env :label "ydotool key Enter"}))))

(defn- focus-keys-plan [config keys*]
  [{:argv (apply ydotool-argv config "key" (mapcat key-args keys*))
    :env (ydotool-env config) :label "ydotool key"}])

(defn- focus-execute [plan-steps details dry-run]
  (cond
    dry-run (dry-result "focus" details plan-steps)
    :else (if-let [msg (run-plan plan-steps)]
            (err-result "focus" msg)
            (ok-result "focus" details))))

;; --- entry point ---

(defn execute
  "Execute (or dry-run) a router plan against a target
  ({:type :tmux :pane \"s:w.p\"} or {:type :focus}).
  Returns an executor result payload (string keys, JSON-shaped)."
  [{:keys [action text submit keys] :as _plan} target config {:keys [dry-run]}]
  (let [backend (:type target)]
    (cond
      (nil? target)
      (err-result "none" "No target configured (dais-ctl target set ...)")

      (and (= :press-keys action)
           (not (and (seq keys) (every? allowed-key-names keys))))
      (err-result (name backend)
                  (str "Refusing to send keys: " (pr-str keys)
                       " contains a key outside the allowed set."))

      (= :tmux backend)
      (let [pane (:pane target)
            details {"backend" "tmux" "pane" pane "action" (name action)}]
        (case action
          :type-text (tmux-execute (tmux-type-plan config pane text submit)
                                   (assoc details "submit" (boolean submit))
                                   config pane dry-run)
          :press-keys (tmux-execute (tmux-keys-plan config pane keys)
                                    (assoc details "keys" (vec keys))
                                    config pane dry-run)
          (err-result "tmux" (str "Unsupported action: " (pr-str action)))))

      (= :focus backend)
      (let [details {"backend" "focus" "action" (name action)}]
        (case action
          :type-text (focus-execute (focus-type-plan config text submit)
                                    (assoc details "submit" (boolean submit))
                                    dry-run)
          :press-keys (focus-execute (focus-keys-plan config keys)
                                     (assoc details "keys" (vec keys))
                                     dry-run)
          (err-result "focus" (str "Unsupported action: " (pr-str action)))))

      :else
      (err-result "none" (str "Unknown target type: " (pr-str backend))))))
