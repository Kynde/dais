(ns dais.ear
  "Supervision of the resident ear worker (ear/dais_ear.py via pixi).

  The daemon writes ndjson control messages to the ear's stdin and reads
  dais.event.v1 envelopes from its stdout; ear stderr is inherited so
  diagnostics land in the daemon's stderr. The mode-transition -> control
  message mapping lives here as a pure function so it is testable."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.lang ProcessBuilder$Redirect)))

(defn ear-message
  "The control message a mode transition implies, or nil. `via` distinguishes
  a latch stop (transcribe the recording) from voice-off/VAD-stop (discard)."
  [old-mode new-mode via]
  (cond
    (= old-mode new-mode) nil
    (= new-mode :manual-recording) {"type" "latch_start"}
    (= new-mode :vad-listening) {"type" "set_mode" "mode" "vad"}
    (and (= old-mode :manual-recording) (= via "toggle-record")) {"type" "latch_stop"}
    (= new-mode :off) {"type" "set_mode" "mode" "off"}))

(defn start!
  "Spawn the ear worker. opts: :cmd (argv prefix, e.g. pixi run), :dir
  (working directory), :args (worker arguments). `on-event` is called with
  each parsed stdout event (on the reader thread)."
  [{:keys [cmd dir args]} on-event]
  (let [argv (into (vec cmd) args)
        pb (doto (ProcessBuilder. ^java.util.List argv)
             (.redirectError ProcessBuilder$Redirect/INHERIT))
        _ (when dir (.directory pb (io/file dir)))
        proc (.start pb)
        writer (io/writer (.getOutputStream proc))
        reader (Thread.
                (fn []
                  (with-open [r (io/reader (.getInputStream proc))]
                    (doseq [line (line-seq r)]
                      (when-not (str/blank? line)
                        (try
                          (on-event (json/read-str line))
                          (catch Exception e
                            (binding [*out* *err*]
                              (println "ear event error:" (.getMessage e)
                                       "line:" line)))))))))]
    (doto reader (.setDaemon true) (.start))
    {:proc proc :writer writer :lock (Object.)}))

(defn alive? [{:keys [^Process proc]}]
  (boolean (and proc (.isAlive proc))))

(defn send!
  "Write one control message to the ear's stdin. Swallows write failures (a
  dead ear must not break the control path; the daemon notices via alive?)."
  [{:keys [^java.io.Writer writer lock] :as ear} msg]
  (when (and ear (alive? ear))
    (locking lock
      (try
        (.write writer (str (json/write-str msg) "\n"))
        (.flush writer)
        (catch Exception _ nil)))))

(defn stop! [{:keys [^Process proc] :as ear}]
  (when ear
    (try (send! ear {"type" "set_mode" "mode" "off"}) (catch Exception _))
    (when proc (.destroy proc))))
