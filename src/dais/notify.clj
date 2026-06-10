(ns dais.notify
  "Desktop feedback via notify-send. State transitions and errors only — not
  every utterance. Every notification carries a timeout so it folds away on
  its own; no action buttons, nothing waits for a mouse."
  (:require [dais.executor :as executor]))

(defn notify!
  "Fire-and-forget notification. Failures are swallowed — feedback must never
  break the pipeline."
  [config summary & [body]]
  (let [{:keys [enabled timeout-ms cmd]} (:notifications config)]
    (when enabled
      (try
        (executor/run-proc
         ;; suppress-sound: the mic must never hear our own notification chime
         ;; (a VAD-start chime transcribes as "Hello!" — observed in trials).
         (cond-> [(or cmd "/usr/bin/notify-send") "-a" "dais"
                  "-t" (str (or timeout-ms 2500))
                  "-h" "boolean:suppress-sound:true" summary]
           body (conj body))
         {})
        (catch Exception _ nil)))))
