(ns dais.config
  "Load dais runtime configuration (config/dais.edn) and resolve runtime
  paths. Config is EDN on disk; only wire events must be JSON."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def default-config-path "config/dais.edn")

(defn runtime-dir
  "Base dir for runtime artifacts (socket, state.json, marker files).
  Honors $DAIS_RUNTIME_DIR (tests), then $XDG_RUNTIME_DIR."
  []
  (or (System/getenv "DAIS_RUNTIME_DIR")
      (str (or (System/getenv "XDG_RUNTIME_DIR") "/tmp") "/dais")))

(defn socket-path
  "Unix control socket path."
  [config]
  (or (:socket-path config) (str (runtime-dir) "/control.sock")))

(defn events-dir
  "JSONL audit log directory."
  [config]
  (or (:events-dir config) (System/getenv "DAIS_EVENTS_DIR") "events"))

(defn ydotool-socket
  "Socket path for the ydotoold user daemon (the client default unless
  configured). Set explicitly on the subprocess env — never inherited from an
  interactive shell."
  [config]
  (or (get-in config [:focus :ydotool-socket])
      (str (or (System/getenv "XDG_RUNTIME_DIR") "/tmp") "/.ydotool_socket")))

(def defaults
  {:router {:strategy :whole-match :prefix "do" :commands {}}
   :enter-mode :no-enter
   :targets {1 {:type :focus}}
   :active-slot 1
   :tmux ["tmux"]
   :focus {:ydotool ["/usr/bin/ydotool"] :key-delay-ms 2}
   :notifications {:enabled true :timeout-ms 2500}})

(defn- deep-merge [a b]
  (if (and (map? a) (map? b)) (merge-with deep-merge a b) b))

(defn load-config
  "Load config from path (default config/dais.edn, override with
  $DAIS_CONFIG), layered over `defaults`. Missing file yields defaults."
  ([] (load-config (or (System/getenv "DAIS_CONFIG") default-config-path)))
  ([path]
   (let [f (io/file path)]
     (deep-merge defaults (if (.exists f) (edn/read-string (slurp f)) {})))))
