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
   :focus {:ydotool ["/usr/bin/ydotool"]
           :ydotool-socket "/tmp/dais-test-nope.sock"}
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
    (is (= {"type" "focus" "alive" false} (get-in listing ["targets" "2"]))
        "focus liveness = ydotoold socket existence (test config points nowhere)")
    (is (= false (get-in listing ["targets" "1" "alive"]))
        "tmux liveness = pane resolves (scratch server has no panes)"))
  (testing "bad target value"
    (is (false? (get (req {"op" "target" "action" "set" "slot" 1 "pane" "garbage"}) "ok"))))
  (testing "voice next target cycles"
    (inject "next target")
    (is (= 3 (get (req {"op" "query" "query" "status"}) "active_slot")))))

(deftest target-next-plain-cycle
  ;; "next" is a blind cycle (matches voice "next target") — ignores liveness.
  (req {"op" "target" "action" "set" "slot" 3 "pane" "work:2.0"})
  (req {"op" "target" "action" "use" "slot" 2})
  (let [resp (req {"op" "target" "action" "next"})]
    (is (true? (get resp "ok")))
    (is (= 3 (get resp "slot")))
    (is (= 3 (get (req {"op" "query" "query" "status"}) "active_slot"))))
  (testing "wraps to slot 1 even though it is undeliverable"
    (is (= 1 (get (req {"op" "target" "action" "next"}) "slot")))))

(deftest target-prev-cycle
  (req {"op" "target" "action" "set" "slot" 3 "pane" "work:2.0"}) ; slots 1 2 3, active 1
  (let [resp (req {"op" "target" "action" "prev"})]
    (is (true? (get resp "ok")))
    (is (= 3 (get resp "slot")) "1 wraps back to 3")
    (is (= 3 (get (req {"op" "query" "query" "status"}) "active_slot"))))
  (is (= 2 (get (req {"op" "target" "action" "prev"}) "slot")) "3 -> 2"))

(deftest query-commands-lists-voice-vocabulary
  (let [resp (req {"op" "query" "query" "commands"})]
    (is (true? (get resp "ok")))
    (is (= "whole-match" (get resp "strategy")))
    (testing "built-in whole-utterance commands are listed"
      (let [says (set (map #(get % "say") (get resp "commands")))]
        (is (contains? says "scratch that"))
        (is (contains? says "yes"))))
    (testing "daemon controls + keypress grammar are listed"
      (is (seq (get resp "controls")))
      (is (contains? (set (get-in resp ["keypress" "triggers"])) "press"))
      (is (contains? (set (get-in resp ["keypress" "keys"])) "Enter")))))

(deftest query-commands-description-and-config-flag
  (let [cfg (assoc-in config [:router :commands]
                      {"deploy" {:keys ["C-M-t"] :description "open a terminal"}})
        ctx (daemon/make-ctx {:config cfg
                              :events-dir (tmp-dir "dais-ev")
                              :runtime-dir (tmp-dir "dais-rt")
                              :dry-run true})
        resp (daemon/handle-request ctx {"op" "query" "query" "commands"})
        by-say (into {} (map (fn [c] [(get c "say") c]) (get resp "commands")))]
    (testing "config command: :description preferred, config flag true"
      (is (= "open a terminal" (get-in by-say ["deploy" "does"])))
      (is (true? (get-in by-say ["deploy" "config"]))))
    (testing "built-in command: synthesized does, config flag false"
      (is (= "→ C-u" (get-in by-say ["scratch that" "does"])))
      (is (false? (get-in by-say ["scratch that" "config"]))))))

(defn- macro-ctx [macro-cmd]
  (daemon/make-ctx {:config (assoc-in config [:router :commands] {"go" macro-cmd})
                    :events-dir (tmp-dir "dais-ev")
                    :runtime-dir (tmp-dir "dais-rt")
                    :dry-run true}))

(defn- fire [ctx text]
  (let [resp (daemon/handle-request
              ctx {"op" "publish"
                   "event" (event/make-event {:type "voice.transcript"
                                              :source {"module" "test"}
                                              :payload {"text" text}})})]
    (->> (get resp "events")
         (filter #(#{"action.executed" "action.error"} (get % "type")))
         first)))

(deftest macro-runs-steps-in-order
  ;; active slot 1 is the tmux target; dry-run yields would_run per step.
  (let [action (fire (macro-ctx {:macro [{:text "one" :submit true}
                                         {:delay 100}
                                         {:keys ["C-M-t"]}]
                                 :delay 0})
                     "go")
        results (get-in action ["payload" "results"])]
    (is (= "action.executed" (get action "type")))
    (is (= "macro" (get-in action ["payload" "plan" "action"])))
    (is (= 3 (count results)))
    (testing "order preserved: type-text, pause, press-keys"
      (is (= "type-text" (get-in results [0 "plan" "action"])))
      (is (= 100 (get (nth results 1) "pause_ms")))
      (is (= "press-keys" (get-in results [2 "plan" "action"])))
      (is (= ["C-M-t"] (get-in results [2 "plan" "keys"]))))))

(deftest macro-aborts-on-bad-step
  (let [action (fire (macro-ctx {:macro [{:keys ["Enter"]}
                                         {:keys ["Nope"]}   ; unknown key -> error
                                         {:text "never"}]})
                     "go")]
    (is (= "action.error" (get action "type")))
    (is (= 1 (get-in action ["payload" "stopped_at"])) "failed at step index 1")
    (is (= 2 (count (get-in action ["payload" "results"]))) "third step never ran")))

(deftest mute-drops-utterances
  ;; *ctx* is dry-run; mute via the control op (dais-top key / dais-ctl path).
  (is (true? (get (req {"op" "control" "action" "mute"}) "ok")))
  (is (true? (get (req {"op" "query" "query" "status"}) "muted")))
  (testing "muted: utterance dropped, not routed to a plan"
    (let [resp (inject "press enter")]
      (is (= "none" (get-in resp ["plan" "action"])))
      (is (= "muted" (get-in resp ["plan" "reason"])))))
  (testing "unmute resumes routing"
    (is (true? (get (req {"op" "control" "action" "unmute"}) "ok")))
    (is (false? (get (req {"op" "query" "query" "status"}) "muted")))
    (is (= "press-keys" (get-in (inject "press enter") ["plan" "action"])))))

(deftest dry-run-toggle-changes-delivery
  (testing "fixture starts dry-run: plan only, would_run present, nothing executed"
    (let [action (second (get (inject "press enter") "events"))]
      (is (= "action.executed" (get action "type")))
      (is (true? (get-in action ["payload" "dry_run"])))))
  (testing "toggle to live: executor really runs and fails on the scratch pane"
    (req {"op" "control" "action" "toggle-dry-run"})
    (is (= "live" (get (req {"op" "query" "query" "status"}) "execution")))
    (let [action (second (get (inject "press enter") "events"))]
      (is (= "action.error" (get action "type")))
      (is (re-find #"not found" (get-in action ["payload" "error"]))))))

(deftest query-commands-partitions-controls
  (let [resp (req {"op" "query" "query" "commands"})
        controls (set (map #(get % "say") (get resp "controls")))
        commands (set (map #(get % "say") (get resp "commands")))]
    (testing ":control commands are listed as controls"
      (is (contains? controls "voice off"))
      (is (contains? controls "mute"))
      (is (contains? controls "unmute"))
      (is (contains? controls "toggle dry run"))
      (is (contains? controls "target one … five")))   ; parametric descriptor appended
    (testing "typing/keys commands stay in commands"
      (is (contains? commands "scratch that"))
      (is (not (contains? commands "voice off"))))))

(deftest target-next-live-skips-dead
  ;; config has slot 1 (tmux, dead) + slot 2 (focus); make only slot 2 live.
  (with-redefs [daemon/target-alive? (fn [_ t] (= :focus (:type t)))]
    (testing "from a dead active slot, jumps to the next live one"
      (let [resp (req {"op" "target" "action" "next-live"})]
        (is (true? (get resp "ok")))
        (is (= 2 (get resp "slot")))
        (is (= 2 (get (req {"op" "query" "query" "status"}) "active_slot")))))
    (testing "a lone live target stays put rather than erroring"
      (is (= 2 (get (req {"op" "target" "action" "next-live"}) "slot")))))
  (testing "no deliverable targets -> error, slot unchanged"
    (with-redefs [daemon/target-alive? (fn [_ _] false)]
      (let [resp (req {"op" "target" "action" "next-live"})]
        (is (false? (get resp "ok")))
        (is (re-find #"deliverable" (get resp "error")))))))

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
             (dissoc (get-in listing ["targets" "3"]) "alive"))
          "picked target restored"))))

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

(deftest runtime-settings
  (testing "enter-mode change takes effect on routing immediately"
    (is (false? (get-in (inject "run the tests, enter") ["plan" "submit"])))
    (is (true? (get (req {"op" "settings" "enter_mode" "enter-auto"}) "ok")))
    (is (= {"action" "type-text" "text" "run the tests" "submit" true}
           (get (inject "run the tests, enter") "plan"))))
  (testing "strategy change flips routing behavior"
    (req {"op" "settings" "strategy" "prefix"})
    (is (= "type-text" (get-in (inject "press enter") ["plan" "action"]))
        "unprefixed command-looking text is dictation under :prefix")
    (is (= "press-keys" (get-in (inject "do press enter") ["plan" "action"])))
    (req {"op" "settings" "strategy" "whole-match"}))
  (testing "status reflects runtime settings"
    (let [st (req {"op" "query" "query" "status"})]
      (is (= "enter-auto" (get st "enter_mode")))
      (is (= "whole-match" (get st "strategy")))))
  (testing "validation"
    (is (false? (get (req {"op" "settings" "enter_mode" "bogus"}) "ok")))
    (is (false? (get (req {"op" "settings"}) "ok")))))

(deftest invalid-and-unknown
  (is (false? (get (req {"op" "publish" "event" {"type" "voice.transcript"}}) "ok")))
  (is (false? (get (req {"op" "nope"}) "ok")))
  (is (false? (get (req {"op" "control" "action" "dance"}) "ok"))))
