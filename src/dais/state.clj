(ns dais.state
  "Daemon session state: mode, target slots, armed flag — plus the on-disk
  reflections (state.json and marker files) that statuslines read.

  Transitions are pure: they take a state map and return {:state s'} on
  success or {:error \"...\"} on refusal. The daemon owns the atom and the
  file writes."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn initial-state
  "Daemon starts with the mic closed (mode :off) regardless of config; only
  targets/active-slot come from config."
  [config]
  {:mode :off
   :armed false
   :muted false
   :speech false
   :targets (:targets config)
   :active-slot (:active-slot config 1)
   ;; Runtime-togglable settings (session-only: config wins on restart).
   :enter-mode (:enter-mode config :no-enter)
   :strategy (get-in config [:router :strategy] :whole-match)
   :last-utterance nil})

(defn active-target [state]
  (get-in state [:targets (:active-slot state)]))

(defn mic-open? [state]
  (contains? #{:vad-listening :manual-recording} (:mode state)))

;; --- transitions ---

(defn toggle-vad [state]
  (case (:mode state)
    :off {:state (assoc state :mode :vad-listening)}
    :vad-listening {:state (assoc state :mode :off)}
    :manual-recording {:error "manual recording in progress; stop it first"}))

(defn toggle-record [state]
  (case (:mode state)
    :off {:state (assoc state :mode :manual-recording)}
    :manual-recording {:state (assoc state :mode :off)}
    ;; v1 rule (FABLE_PLAN): no latch during a VAD session. The pause/resume
    ;; interplay is a later design, not an accident of implementation.
    :vad-listening {:error "VAD session active; stop it first (F9 or \"voice off\")"}))

(defn voice-off [state]
  {:state (assoc state :mode :off)})

(defn arm [state]
  {:state (assoc state :armed true)})

(defn mute [state] {:state (assoc state :muted true)})
(defn unmute [state] {:state (assoc state :muted false)})

(defn toggle-dry-run
  "Flip the runtime dry-run flag (seeded from the launch flag in make-ctx)."
  [state]
  {:state (update state :dry-run not)})

(defn set-slot [state n]
  (if (get-in state [:targets n])
    {:state (assoc state :active-slot n)}
    {:error (str "no target slot " n)}))

(defn- cycle-slot [state step]
  (let [slots (vec (sort (keys (:targets state))))]
    (if (empty? slots)
      {:error "no target slots configured"}
      (let [i (.indexOf ^java.util.List slots (:active-slot state))
            n (nth slots (mod (+ i step) (count slots)))]
        {:state (assoc state :active-slot n)}))))

(defn next-slot [state] (cycle-slot state 1))
(defn prev-slot [state] (cycle-slot state -1))

(defn set-target [state n target]
  {:state (assoc-in state [:targets n] target)})

;; --- on-disk reflection ---

(defn- target-label [t]
  (when t (if (= :focus (:type t)) "focus" (:pane t))))

(defn write-files!
  "Write state.json plus the mic-recording / speech-detected marker files
  under dir. Markers exist so a statusline can test `[ -f ... ]` instead of
  parsing JSON; both views come from this one function."
  [dir state]
  (let [sj (io/file dir "state.json")]
    (io/make-parents sj)
    (spit sj (json/write-str
              {"mode" (name (:mode state))
               "armed" (boolean (:armed state))
               "muted" (boolean (:muted state))
               "dry_run" (boolean (:dry-run state))
               "speech" (boolean (:speech state))
               "active_slot" (:active-slot state)
               "target" (target-label (active-target state))
               "enter_mode" (name (:enter-mode state :no-enter))
               "strategy" (name (:strategy state :whole-match))
               "last_utterance" (:last-utterance state)}))
    (let [marker (fn [fname on?]
                   (let [f (io/file dir fname)]
                     (if on? (spit f "") (io/delete-file f true))))]
      (marker "mic-recording" (mic-open? state))
      (marker "speech-detected" (boolean (:speech state)))
      (marker "muted" (boolean (:muted state)))
      (marker "dry-run" (boolean (:dry-run state))))))

(defn cleanup-files!
  "Remove socket-adjacent state artifacts on shutdown (markers say nothing is
  recording once the daemon is gone)."
  [dir]
  (doseq [fname ["mic-recording" "speech-detected" "muted" "dry-run"]]
    (io/delete-file (io/file dir fname) true)))

;; --- durable target persistence ---
;; Picked targets survive daemon restarts: config/dais.edn holds the
;; hand-written defaults, targets.edn (under ~/.local/state/dais) overlays
;; whatever was last picked via CLI/voice/TUI.

(defn save-targets!
  [state-dir state]
  (let [f (io/file state-dir "targets.edn")]
    (io/make-parents f)
    (spit f (pr-str {:targets (:targets state)
                     :active-slot (:active-slot state)}))))

(defn load-targets
  "Persisted {:targets :active-slot} overlay, or nil. A corrupt/absent file
  silently yields nil (config defaults win)."
  [state-dir]
  (let [f (io/file state-dir "targets.edn")]
    (when (.exists f)
      (try
        (let [{:keys [targets active-slot] :as m} (edn/read-string (slurp f))]
          (when (and (map? targets) (get targets active-slot))
            m))
        (catch Exception _ nil)))))
