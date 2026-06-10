(ns dais.state
  "Daemon session state: mode, target slots, armed flag — plus the on-disk
  reflections (state.json and marker files) that statuslines read.

  Transitions are pure: they take a state map and return {:state s'} on
  success or {:error \"...\"} on refusal. The daemon owns the atom and the
  file writes."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn initial-state
  "Daemon starts with the mic closed (mode :off) regardless of config; only
  targets/active-slot come from config."
  [config]
  {:mode :off
   :armed false
   :speech false
   :targets (:targets config)
   :active-slot (:active-slot config 1)
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

(defn set-slot [state n]
  (if (get-in state [:targets n])
    {:state (assoc state :active-slot n)}
    {:error (str "no target slot " n)}))

(defn next-slot [state]
  (let [slots (vec (sort (keys (:targets state))))]
    (if (empty? slots)
      {:error "no target slots configured"}
      (let [i (.indexOf ^java.util.List slots (:active-slot state))
            n (nth slots (mod (inc i) (count slots)))]
        {:state (assoc state :active-slot n)}))))

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
               "speech" (boolean (:speech state))
               "active_slot" (:active-slot state)
               "target" (target-label (active-target state))
               "last_utterance" (:last-utterance state)}))
    (let [marker (fn [fname on?]
                   (let [f (io/file dir fname)]
                     (if on? (spit f "") (io/delete-file f true))))]
      (marker "mic-recording" (mic-open? state))
      (marker "speech-detected" (boolean (:speech state))))))

(defn cleanup-files!
  "Remove socket-adjacent state artifacts on shutdown (markers say nothing is
  recording once the daemon is gone)."
  [dir]
  (doseq [fname ["mic-recording" "speech-detected"]]
    (io/delete-file (io/file dir fname) true)))
