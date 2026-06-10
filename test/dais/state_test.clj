(ns dais.state-test
  (:require [clojure.test :refer [deftest is testing]]
            [dais.state :as state]))

(def config
  {:targets {1 {:type :focus} 2 {:type :tmux :pane "app:1.2"}}
   :active-slot 1})

(deftest starts-off
  (let [st (state/initial-state config)]
    (is (= :off (:mode st)))
    (is (false? (:armed st)))
    (is (= {:type :focus} (state/active-target st)))))

(deftest vad-toggle
  (let [st (state/initial-state config)
        on (:state (state/toggle-vad st))
        off (:state (state/toggle-vad on))]
    (is (= :vad-listening (:mode on)))
    (is (state/mic-open? on))
    (is (= :off (:mode off)))
    (is (not (state/mic-open? off)))))

(deftest record-toggle
  (let [st (state/initial-state config)
        rec (:state (state/toggle-record st))]
    (is (= :manual-recording (:mode rec)))
    (is (= :off (:mode (:state (state/toggle-record rec)))))))

(deftest v1-rule-no-latch-during-vad
  (let [vad (:state (state/toggle-vad (state/initial-state config)))]
    (is (some? (:error (state/toggle-record vad))))
    (is (nil? (:state (state/toggle-record vad))))))

(deftest no-vad-toggle-during-recording
  (let [rec (:state (state/toggle-record (state/initial-state config)))]
    (is (some? (:error (state/toggle-vad rec))))))

(deftest voice-off-from-anywhere
  (doseq [mode [:off :vad-listening :manual-recording]]
    (is (= :off (:mode (:state (state/voice-off
                                (assoc (state/initial-state config) :mode mode))))))))

(deftest slots
  (let [st (state/initial-state config)]
    (testing "set-slot validates existence"
      (is (= 2 (:active-slot (:state (state/set-slot st 2)))))
      (is (some? (:error (state/set-slot st 9)))))
    (testing "next-slot cycles"
      (let [s2 (:state (state/next-slot st))
            s1 (:state (state/next-slot s2))]
        (is (= 2 (:active-slot s2)))
        (is (= 1 (:active-slot s1)))))
    (testing "set-target adds a slot"
      (is (= {:type :tmux :pane "x:0.0"}
             (get-in (:state (state/set-target st 3 {:type :tmux :pane "x:0.0"}))
                     [:targets 3]))))))

(deftest state-files
  (let [dir (str (System/getProperty "java.io.tmpdir") "/dais-state-test-" (random-uuid))
        st (assoc (state/initial-state config) :mode :vad-listening :speech true)]
    (state/write-files! dir st)
    (is (.exists (java.io.File. dir "state.json")))
    (is (.exists (java.io.File. dir "mic-recording")))
    (is (.exists (java.io.File. dir "speech-detected")))
    (state/write-files! dir (state/initial-state config))
    (is (not (.exists (java.io.File. dir "mic-recording"))))
    (is (not (.exists (java.io.File. dir "speech-detected"))))
    (state/cleanup-files! dir)))
