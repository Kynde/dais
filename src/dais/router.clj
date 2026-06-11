(ns dais.router
  "Pure routing: transcript text -> planned action. No I/O.

  The keypress grammar is ported from lausu (src/lausu/intent.clj, field-
  tested): an utterance is a key-press command iff it starts with a trigger
  verb AND every non-filler token maps to a whitelisted key. That last rule is
  the safety property — \"select the right abstraction for this\" falls
  through to dictation, and in armed/prefixed contexts a failed match executes
  NOTHING rather than being typed.

  Plans returned:
    {:action :press-keys :keys [\"Enter\"]}
    {:action :type-text :text \"...\" :submit bool}
    {:action :control :control :voice-off | :next-target | :set-slot (:slot n)}
    {:action :none :reason \"...\"}"
  (:require [clojure.string :as str]))

(def key-words
  "Spoken word -> whitelisted key name. Key names are the executor's
  vocabulary (tmux send-keys names; the focus backend maps them to keycodes)."
  {"enter" "Enter" "return" "Enter"
   "escape" "Escape" "esc" "Escape"
   "up" "Up" "down" "Down" "left" "Left" "right" "Right"
   "tab" "Tab" "backspace" "BSpace"
   "one" "1" "two" "2" "three" "3" "four" "4" "five" "5"
   "six" "6" "seven" "7" "eight" "8" "nine" "9"
   "first" "1" "second" "2" "third" "3" "fourth" "4" "fifth" "5"
   "sixth" "6" "seventh" "7" "eighth" "8" "ninth" "9"})

(def keypress-triggers
  "Verbs that mark a whole utterance as a key-press command."
  #{"press" "hit" "push" "choose" "select" "pick" "answer"})

(def ^:private keypress-trigger-re
  (re-pattern (str "^\\s*(?:" (str/join "|" keypress-triggers) ")\\b")))

(def ^:private connective-fillers
  "Glue words dropped before mapping the remaining tokens to keys."
  #{"the" "a" "an" "and" "then" "key" "keys" "option" "number" "button"})

(def ^:private keypress-fillers (into keypress-triggers connective-fillers))

(def default-commands
  "Built-in whole-utterance commands; config :router :commands merges over
  these. {:keys [...]} presses a key sequence, {:text ...} types a reply."
  {"scratch that" {:keys ["C-u"]}
   "cancel" {:keys ["Escape"]}
   "stop" {:keys ["Escape"]}
   "yes" {:text "yes" :submit true}
   "no" {:text "no" :submit true}})

(defn normalize
  "Lowercase, strip punctuation to spaces, collapse whitespace. Used for
  command matching only — dictation keeps the original text."
  [s]
  (-> (or s "")
      str/lower-case
      (str/replace #"[^a-z0-9]+" " ")
      str/trim))

(def ^:private ctrl-letters
  "Letters pronounceable after control/ctrl, limited to the executor's
  whitelisted C-* chords."
  {"a" "C-a" "c" "C-c" "d" "C-d" "k" "C-k" "u" "C-u" "w" "C-w"})

(defn- pair-ctrl
  "Fold [\"control\" \"a\"] / [\"ctrl\" \"w\"] token pairs into C-* key tokens.
  Runs BEFORE filler removal — \"a\" is a filler word and would vanish."
  [tokens]
  (loop [ts tokens out []]
    (cond
      (empty? ts) out
      (and (#{"control" "ctrl"} (first ts)) (ctrl-letters (second ts)))
      (recur (drop 2 ts) (conj out (ctrl-letters (second ts))))
      :else (recur (rest ts) (conj out (first ts))))))

(defn keypress-request
  "When the WHOLE normalized utterance is a key-press instruction, return the
  ordered key names (capped at 5), else nil."
  [norm]
  (when (re-find keypress-trigger-re norm)
    (let [tokens (pair-ctrl (str/split norm #" +"))
          remaining (remove keypress-fillers tokens)
          keys* (mapv (fn [w] (or (key-words w)
                                  (when (re-matches #"C-[a-z]" w) w)
                                  (when (re-matches #"[1-9]" w) w)))
                      remaining)]
      (when (and (seq keys*)
                 (<= (count keys*) 5)
                 (every? some? keys*))
        keys*))))

(def ^:private slot-words
  {"one" 1 "two" 2 "three" 3 "four" 4 "five" 5
   "1" 1 "2" 2 "3" 3 "4" 4 "5" 5
   "first" 1 "second" 2 "third" 3 "fourth" 4 "fifth" 5})

(defn control-request
  "Daemon-level voice commands. These work in every strategy — \"voice off\"
  must always be an escape hatch."
  [norm]
  (cond
    (#{"voice off" "dais off"} norm) {:action :control :control :voice-off}
    (= "next target" norm) {:action :control :control :next-target}
    :else
    (when-let [[_ w] (re-matches #"target (\w+)" norm)]
      (when-let [n (slot-words w)]
        {:action :control :control :set-slot :slot n}))))

(defn- command-match [norm commands]
  (when-let [entry (get commands norm)]
    (cond
      (:keys entry) {:action :press-keys :keys (vec (:keys entry))}
      (:text entry) {:action :type-text :text (:text entry)
                     :submit (boolean (:submit entry))})))

(defn- as-command [norm commands]
  (or (control-request norm)
      (command-match norm commands)
      (when-let [ks (keypress-request norm)]
        {:action :press-keys :keys ks})))

(defn single-line
  "Whisper segments arrive joined by whitespace/newlines; dictation is
  delivered as ONE line so the agent's input box shows the text instead of a
  collapsed multi-line paste."
  [s]
  (-> (or s "") str/trim (str/replace #"\s+" " ")))

(defn- strip-trailing-enter
  "For :enter-auto — text ending in the spoken word \"enter\" (fuzzy on
  trailing punctuation) returns the text without it, else nil."
  [t]
  (when-let [[_ kept] (re-find #"(?i)^(.*?)[\s,.!?]+enter[\s.!?]*$" t)]
    (str/trim kept)))

(defn- dictation [text enter-mode]
  (let [t (single-line text)]
    (case enter-mode
      :enter-always {:action :type-text :text t :submit true}
      :enter-auto (if (= "enter" (normalize t))
                    {:action :type-text :text "" :submit true}
                    (if-let [kept (strip-trailing-enter t)]
                      {:action :type-text :text kept :submit true}
                      {:action :type-text :text t :submit false}))
      {:action :type-text :text t :submit false})))

(defn merged-commands
  "default-commands + config commands, phrase keys normalized once."
  [config-commands]
  (into {} (map (fn [[k v]] [(normalize k) v])
                (merge default-commands config-commands))))

(def control-phrases
  "Always-available daemon voice commands, for help UIs. Description only —
  the matching logic lives in control-request; keep the two in sync."
  [["voice off / dais off" "stop listening"]
   ["next target"          "cycle to the next target slot"]
   ["target one … five"    "switch to that slot (digits & ordinals too)"]])

(defn vocabulary
  "Static voice-command reference for help UIs. Callers layer on the live
  merged :commands map (it is config-dependent) and the active strategy."
  []
  {:controls control-phrases
   :triggers (vec (sort keypress-triggers))
   :ignored  (vec (sort connective-fillers))
   :keys     (vec (sort (distinct (vals key-words))))})

(defn- strip-prefix [norm prefix]
  (when (str/starts-with? norm (str prefix " "))
    (subs norm (inc (count prefix)))))

(defn route
  "Classify one utterance. opts:
    :strategy   :whole-match | :prefix | :key-armed
    :commands   normalized command map (see merged-commands)
    :enter-mode :no-enter | :enter-auto | :enter-always
    :armed      true when the next utterance was armed as command-only
    :prefix     spoken prefix word for :prefix strategy"
  [text {:keys [strategy commands enter-mode armed prefix]
         :or {strategy :whole-match enter-mode :no-enter prefix "do"}}]
  (let [norm (normalize text)]
    (cond
      (str/blank? norm)
      {:action :none :reason "empty transcript"}

      ;; Armed: the utterance MUST be a command; a miss does nothing.
      armed
      (or (as-command norm commands)
          {:action :none :reason "armed: not a recognized command"})

      :else
      (case strategy
        :whole-match
        (or (as-command norm commands)
            (dictation text enter-mode))

        :prefix
        (if-let [rest* (strip-prefix norm prefix)]
          (or (as-command rest* commands)
              {:action :none :reason "prefixed: not a recognized command"})
          (or (control-request norm)
              (dictation text enter-mode)))

        :key-armed
        (or (control-request norm)
            (dictation text enter-mode))))))
