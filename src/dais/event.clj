(ns dais.event
  "Event envelope construction and shape validation (dais.event.v1).

  Pure functions only — no I/O. Adapted from lausu.event; dais drops the
  `mode` field (routing is daemon state, not an envelope property).

  Events are plain Clojure maps with string keys so they round-trip through
  JSON unchanged (clojure.data.json emits/reads string keys by default)."
  (:require [clojure.data.json :as json])
  (:import (java.time Instant)
           (java.util UUID)))

(def schema-version "dais.event.v1")

(defn now-iso
  "Current time as an RFC 3339 / ISO-8601 string (UTC)."
  []
  (str (Instant/now)))

(defn new-id
  "Fresh globally-unique event id."
  []
  (str (UUID/randomUUID)))

(defn make-event
  "Build an event envelope map.

  Required opts: :type, :payload.
  Optional: :id, :time, :source, :parent-id, :trace-id, :sequence.
  Keys are emitted as strings to match the JSON wire shape exactly."
  [{:keys [type payload id time source parent-id trace-id sequence]
    :or {id (new-id) time (now-iso) source {"module" "dais"}}}]
  (when (nil? type) (throw (ex-info "event :type is required" {})))
  (when (nil? payload) (throw (ex-info "event :payload is required" {:type type})))
  (cond-> {"schema" schema-version
           "id" id
           "time" time
           "type" type
           "source" source
           "payload" payload}
    parent-id (assoc "parent_id" parent-id)
    trace-id (assoc "trace_id" trace-id)
    sequence (assoc "sequence" sequence)))

(defn valid-envelope?
  "True when m has all required envelope fields present and non-nil. `type`
  must be a dot-name string and `payload` a map."
  [m]
  (boolean
   (and (map? m)
        (= schema-version (get m "schema"))
        (string? (get m "id")) (seq (get m "id"))
        (string? (get m "time")) (seq (get m "time"))
        (let [t (get m "type")]
          (and (string? t) (re-matches #"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+" t)))
        (map? (get m "source")) (string? (get-in m ["source" "module"]))
        (map? (get m "payload")))))

(defn ->json-line
  "Serialize an event to a single-line JSON string (no trailing newline)."
  [event]
  (json/write-str event))

(defn parse-json-line
  "Parse one JSON line into an event map with string keys."
  [line]
  (json/read-str line))
