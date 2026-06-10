(ns dais.log
  "Append-only JSONL audit log (events/YYYY-MM-DD.jsonl), replayable by
  trace_id. Adapted from lausu.log. Appends are serialized through a lock so
  concurrent socket connections cannot interleave lines."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.time LocalDate)))

(def ^:private write-lock (Object.))

(defn- date-stamp []
  (str (LocalDate/now)))

(defn log-file
  "Path to today's JSONL log under dir."
  [dir]
  (io/file dir (str (date-stamp) ".jsonl")))

(defn append!
  "Append one event (a map) as a JSON line to today's log under dir.
  Creates dir if needed. Returns the event unchanged."
  [dir event]
  (locking write-lock
    (let [f (log-file dir)]
      (io/make-parents f)
      (with-open [w (io/writer f :append true)]
        (.write w (json/write-str event))
        (.write w "\n"))))
  event)

(defn append-all!
  "Append several events in order under the same lock."
  [dir events]
  (locking write-lock
    (let [f (log-file dir)]
      (io/make-parents f)
      (with-open [w (io/writer f :append true)]
        (doseq [event events]
          (.write w (json/write-str event))
          (.write w "\n")))))
  events)

(defn read-all
  "Read every event from every *.jsonl file under dir, in filename then line
  order. Returns a vector of event maps. Missing dir -> empty."
  [dir]
  (let [d (io/file dir)]
    (if (.isDirectory d)
      (->> (.listFiles d)
           (filter #(str/ends-with? (.getName %) ".jsonl"))
           sort
           (mapcat (fn [f]
                     (with-open [r (io/reader f)]
                       (->> (line-seq r)
                            (remove str/blank?)
                            (mapv json/read-str)))))
           vec)
      [])))

(defn last-n
  "Return the last n logged events across all logs under dir."
  [dir n]
  (vec (take-last n (read-all dir))))

(defn by-trace
  "Return all logged events sharing the given trace_id, in log order."
  [dir trace-id]
  (filterv #(= trace-id (get % "trace_id")) (read-all dir)))
