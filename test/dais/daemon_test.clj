(ns dais.daemon-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dais.daemon :as daemon]
            [dais.event :as event])
  (:import (java.nio.file Files)))

(def config
  {:router {:strategy :whole-match :prefix "do" :commands {}}
   :enter-mode :no-enter
   :targets {1 {:type :tmux :pane "app:1.2"}
             2 {:type :focus}}
   :active-slot 1
   :tmux ["tmux" "-L" "dais-test-never-running"]
   :focus {:ydotool ["/usr/bin/ydotool"]}
   :notifications {:enabled false}})

(def ^:dynamic *ctx* nil)

(defn- tmp-dir [prefix]
  (str (Files/createTempDirectory prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(use-fixtures :each
  (fn [f]
    (binding [*ctx* (daemon/make-ctx {:config config
                                      :events-dir (tmp-dir "dais-events")
                                      :runtime-dir (tmp-dir "dais-runtime")
                                      :dry-run true})]
      (f))))

(defn- req [m] (daemon/handle-request *ctx* m))

(defn- inject [text]
  (req {"op" "publish"
        "event" (event/make-event {:type "voice.transcript"
                                   :source {"module" "test"}
                                   :payload {"text" text}})}))

(deftest inject-command-presses-enter
  (let [resp (inject "press enter")]
    (is (true? (get resp "ok")))
    (is (= {"action" "press-keys" "keys" ["Enter"]} (get resp "plan")))
    (let [[transcript action] (get resp "events")]
      (is (= "voice.transcript" (get transcript "type")))
      (is (= "action.executed" (get action "type")))
      (is (= (get transcript "trace_id") (get action "trace_id")))
      (is (seq (get-in action ["payload" "details" "would_run"]))))))

(deftest inject-dictation-types-text
  (let [resp (inject "please review PR #123")]
    (is (= "type-text" (get-in resp ["plan" "action"])))
    (is (false? (get-in resp ["plan" "submit"])))
    (is (= "action.executed" (get (second (get resp "events")) "type")))))

(deftest inject-voice-off-changes-state
  (req {"op" "control" "action" "toggle-vad"})
  (let [resp (inject "voice off")]
    (is (true? (get resp "ok")))
    (is (= "control.state_changed" (get (second (get resp "events")) "type"))))
  (is (= "off" (get (req {"op" "query" "query" "status"}) "mode"))))

(deftest control-transitions-and-refusals
  (is (true? (get (req {"op" "control" "action" "toggle-vad"}) "ok")))
  (is (= "vad-listening" (get (req {"op" "query" "query" "status"}) "mode")))
  (testing "v1 rule: latch refused during VAD"
    (let [resp (req {"op" "control" "action" "toggle-record"})]
      (is (false? (get resp "ok")))
      (is (re-find #"VAD" (get resp "error")))))
  (is (true? (get (req {"op" "control" "action" "voice-off"}) "ok"))))

(deftest armed-is-single-shot
  (req {"op" "control" "action" "arm"})
  (is (true? (get (req {"op" "query" "query" "status"}) "armed")))
  (let [resp (inject "this is not a command")]
    (is (= "none" (get-in resp ["plan" "action"])))
    (is (= "action.error" (get (second (get resp "events")) "type"))))
  (is (false? (get (req {"op" "query" "query" "status"}) "armed"))))

(deftest target-ops
  (let [resp (req {"op" "target" "action" "set" "slot" 3 "pane" "work:2.0"})]
    (is (true? (get resp "ok")))
    (is (= {"type" "tmux" "pane" "work:2.0"} (get resp "target"))))
  (is (true? (get (req {"op" "target" "action" "use" "slot" 2}) "ok")))
  (let [listing (req {"op" "target" "action" "list"})]
    (is (= 2 (get listing "active_slot")))
    (is (= {"type" "focus"} (get-in listing ["targets" "2"]))))
  (testing "bad target value"
    (is (false? (get (req {"op" "target" "action" "set" "slot" 1 "pane" "garbage"}) "ok"))))
  (testing "voice next target cycles"
    (inject "next target")
    (is (= 3 (get (req {"op" "query" "query" "status"}) "active_slot")))))

(deftest events-and-replay
  (let [resp (inject "press enter")
        trace (get resp "trace_id")
        replayed (req {"op" "replay" "trace_id" trace})]
    (is (= 2 (count (get replayed "events"))))
    (is (every? #(= trace (get % "trace_id")) (get replayed "events"))))
  (is (pos? (count (get (req {"op" "events" "last" 10}) "events")))))

(deftest broadcast-pushes-and-drops-dead-subscribers
  (let [good (java.io.StringWriter.)
        dead (proxy [java.io.Writer] []
               (write [_] (throw (java.io.IOException. "gone")))
               (flush [] (throw (java.io.IOException. "gone")))
               (close []))]
    (daemon/subscribe! *ctx* good)
    (daemon/subscribe! *ctx* dead)
    (daemon/broadcast! *ctx* {"type" "asr.level" "payload" {"rms" 0.1}})
    (is (re-find #"asr\.level" (str good)))
    (is (= #{good} @(:subscribers *ctx*))
        "throwing writer was dropped from the registry")
    (daemon/unsubscribe! *ctx* good)))

(deftest level-events-broadcast-but-never-logged
  (let [sub (java.io.StringWriter.)]
    (daemon/subscribe! *ctx* sub)
    (#'daemon/on-ear-event *ctx* {"type" "asr.level"
                                  "payload" {"rms" 0.2 "prob" 0.9 "speech" true}})
    (is (re-find #"asr\.level" (str sub)) "subscriber received the level event")
    (is (empty? (filter #(= "asr.level" (get % "type"))
                        (get (req {"op" "events" "last" 100}) "events")))
        "level events never reach the audit log")
    (daemon/unsubscribe! *ctx* sub)))

(deftest subscriber-receives-recorded-events
  (let [sub (java.io.StringWriter.)]
    (daemon/subscribe! *ctx* sub)
    (inject "press enter")
    (is (re-find #"voice\.transcript" (str sub)))
    (is (re-find #"action\.executed" (str sub)))
    (daemon/unsubscribe! *ctx* sub)))

(deftest picked-targets-survive-restart
  (let [state-dir (tmp-dir "dais-statedir")
        mk #(daemon/make-ctx {:config config
                              :events-dir (tmp-dir "dais-events")
                              :runtime-dir (tmp-dir "dais-runtime")
                              :state-dir state-dir
                              :dry-run true})
        ctx1 (mk)]
    (daemon/handle-request ctx1 {"op" "target" "action" "set" "slot" 3 "pane" "work:claude.0"})
    (daemon/handle-request ctx1 {"op" "target" "action" "use" "slot" 3})
    (let [ctx2 (mk)
          listing (daemon/handle-request ctx2 {"op" "target" "action" "list"})]
      (is (= 3 (get listing "active_slot")) "active slot restored")
      (is (= {"type" "tmux" "pane" "work:claude.0"}
             (get-in listing ["targets" "3"])) "picked target restored"))))

(deftest panes-listing-fails-safe-without-tmux
  ;; scratch server name has no server: the op reports an error, never guesses
  (let [resp (req {"op" "target" "action" "panes"})]
    (is (false? (get resp "ok")))
    (is (re-find #"tmux" (get resp "error")))))

(deftest idle-timeout-closes-vad-session
  (let [ctx (assoc *ctx* :config (assoc config :vad-idle-off-min 0.001))] ; 60ms
    (daemon/handle-request ctx {"op" "control" "action" "toggle-vad"})
    (testing "recent activity: no-op"
      (daemon/check-idle! ctx)
      (is (= "vad-listening" (get (daemon/handle-request ctx {"op" "query" "query" "status"}) "mode"))))
    (testing "stale activity: forced off, logged as idle-timeout"
      (reset! (:last-heard ctx) (- (System/currentTimeMillis) 1000))
      (daemon/check-idle! ctx)
      (is (= "off" (get (daemon/handle-request ctx {"op" "query" "query" "status"}) "mode")))
      (is (some #(and (= "control.state_changed" (get % "type"))
                      (= "idle-timeout" (get-in % ["payload" "via"])))
                (get (daemon/handle-request ctx {"op" "events" "last" 10}) "events"))))
    (testing "no-op outside vad mode and when disabled"
      (daemon/check-idle! ctx)
      (let [off-ctx (assoc ctx :config (assoc config :vad-idle-off-min nil))]
        (daemon/handle-request off-ctx {"op" "control" "action" "toggle-vad"})
        (reset! (:last-heard off-ctx) 0)
        (daemon/check-idle! off-ctx)
        (is (= "vad-listening" (get (daemon/handle-request off-ctx {"op" "query" "query" "status"}) "mode")))))))

(deftest invalid-and-unknown
  (is (false? (get (req {"op" "publish" "event" {"type" "voice.transcript"}}) "ok")))
  (is (false? (get (req {"op" "nope"}) "ok")))
  (is (false? (get (req {"op" "control" "action" "dance"}) "ok"))))
