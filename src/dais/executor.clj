(ns dais.executor
  "Render router plans through a delivery backend, chosen by the active
  target: :tmux (paste-buffer / send-keys to a pane) or :focus (ydotool to
  the focused app).

  Invariants (inherited from lausu's executor):
  - Subprocesses run from argv vectors, never shell strings. Dictation text
    travels on stdin (tmux load-buffer - / ydotool type --file -), so it is
    never an argv element.
  - Keys are validated against a chord vocabulary (known modifiers + base);
    free-form text can never become key input.
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

;; --- key chord vocabulary ---------------------------------------------------
;; Keys are tmux-style chords: modifier prefixes + a base key, e.g. "C-M-t"
;; (ctrl-alt-t), "M-Right", "Enter", "C-a", "1". Each chord renders to a tmux
;; send-keys token AND a ydotool keycode vector, so the same config command
;; works on either backend. Config commands/macros are trusted authors; the
;; *router* (voice) only ever emits its own narrow grammar, so widening this
;; vocabulary does not widen what speech can synthesize. Validation only
;; confirms a chord is renderable (known modifier + known base).

(def ^:private modifiers
  "Modifier prefix -> {:tmux <send-keys letter> :code <linux keycode>}."
  {"C" {:tmux "C" :code 29}    ; ctrl  (KEY_LEFTCTRL)
   "M" {:tmux "M" :code 56}    ; alt   (KEY_LEFTALT)
   "S" {:tmux "S" :code 42}    ; shift (KEY_LEFTSHIFT)
   "s" {:tmux "s" :code 125}}) ; super (KEY_LEFTMETA) — focus target only

(def ^:private mod-order
  "Canonical render order so a chord's token/keycodes are deterministic."
  ["C" "M" "S" "s"])

(def ^:private base-keys
  "Base key -> {:tmux <send-keys name> :code <linux input-event-code>}. Letter,
  arrow and Enter codes cross-checked against the historical focus-keycodes."
  (merge
   ;; letters a-z: KEY_A=30, KEY_B=48, ... tmux name is the literal char
   (into {} (map (fn [[k c]] [k {:tmux k :code c}])
                 {"a" 30 "b" 48 "c" 46 "d" 32 "e" 18 "f" 33 "g" 34 "h" 35
                  "i" 23 "j" 36 "k" 37 "l" 38 "m" 50 "n" 49 "o" 24 "p" 25
                  "q" 16 "r" 19 "s" 31 "t" 20 "u" 22 "v" 47 "w" 17 "x" 45
                  "y" 21 "z" 44}))
   ;; digits: KEY_1=2..KEY_9=10, KEY_0=11
   (into {} (map (fn [n] [(str n) {:tmux (str n) :code (if (zero? n) 11 (inc n))}])
                 (range 0 10)))
   ;; F1-F12: KEY_F1=59..KEY_F10=68, KEY_F11=87, KEY_F12=88
   (into {} (map (fn [n] [(str "F" n) {:tmux (str "F" n)
                                       :code (cond (<= n 10) (+ 58 n) (= n 11) 87 :else 88)}])
                 (range 1 13)))
   {"Enter"  {:tmux "Enter"    :code 28}
    "Escape" {:tmux "Escape"   :code 1}
    "Tab"    {:tmux "Tab"      :code 15}
    "Space"  {:tmux "Space"    :code 57}
    "BSpace" {:tmux "BSpace"   :code 14}
    "Up"     {:tmux "Up"       :code 103}
    "Down"   {:tmux "Down"     :code 108}
    "Left"   {:tmux "Left"     :code 105}
    "Right"  {:tmux "Right"    :code 106}
    "Home"   {:tmux "Home"     :code 102}
    "End"    {:tmux "End"      :code 107}
    "PgUp"   {:tmux "PageUp"   :code 104}
    "PgDn"   {:tmux "PageDown" :code 109}}))

(defn parse-chord
  "Parse a tmux-style chord (\"C-M-t\") into {:mods #{\"C\" \"M\"} :base \"t\"}, or
  nil if any modifier prefix or the base key is unknown. The base is the final
  hyphen-segment; everything before it must be modifier letters."
  [s]
  (when (string? s)
    (let [parts (str/split s #"-")
          base (last parts)
          mods (butlast parts)]
      (when (and (contains? base-keys base) (every? modifiers mods))
        {:mods (set mods) :base base}))))

(defn valid-chord? [s] (some? (parse-chord s)))

(defn- chord->tmux [{:keys [mods base]}]
  (str (str/join (map #(str (get-in modifiers [% :tmux]) "-")
                      (filter mods mod-order)))
       (get-in base-keys [base :tmux])))

(defn- chord->codes [{:keys [mods base]}]
  (conj (mapv #(get-in modifiers [% :code]) (filter mods mod-order))
        (get-in base-keys [base :code])))

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

(def agent-commands
  "Pane process names that look like coding agents — used only to ANNOTATE
  the pane picker (the send guard was deliberately not ported; see plan)."
  #{"claude" "codex" "copilot" "omp" "pi"})

(defn list-all-panes
  "Every pane across all sessions, window-NAME based specs (stable under
  reordering, matches the user's name-based targets). nil when tmux is
  unreachable."
  [config]
  (let [{:keys [exit out]} (run-proc (into (tmux-prefix config)
                                           ["list-panes" "-a" "-F"
                                            (str "#{session_name}:#{window_name}.#{pane_index}"
                                                 "\t#{pane_current_command}"
                                                 "\t#{pane_current_path}")])
                                     {})]
    (when (zero? exit)
      (vec (for [line (str/split-lines out)
                 :when (not (str/blank? line))
                 :let [[pane cmd path] (str/split line #"\t" 3)]]
             {:pane pane :command cmd :path path})))))

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
  [{:argv (into (tmux-prefix config)
                (into ["send-keys" "-t" pane]
                      (map #(chord->tmux (parse-chord %)) keys*)))
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
  "ydotool key arguments for one chord: press codes in order, release in
  reverse (so chords like C-c / C-M-t work out naturally)."
  [chord]
  (let [codes (chord->codes (parse-chord chord))]
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
           (not (and (seq keys) (every? valid-chord? keys))))
      (err-result (name backend)
                  (str "Refusing to send keys: " (pr-str keys)
                       " contains an unknown key or modifier."))

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
