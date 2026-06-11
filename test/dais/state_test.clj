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
    (testing "prev-slot cycles backward"
      (let [st3 (assoc-in st [:targets 3] {:type :focus})] ; slots 1 2 3, active 1
        (is (= 3 (:active-slot (:state (state/prev-slot st3))))            "1 wraps to 3")
        (is (= 2 (:active-slot (:state (state/prev-slot (assoc st3 :active-slot 3))))) "3 -> 2")))
    (testing "set-target adds a slot"
      (is (= {:type :tmux :pane "x:0.0"}
             (get-in (:state (state/set-target st 3 {:type :tmux :pane "x:0.0"}))
                     [:targets 3]))))))

(deftest target-persistence
  (let [dir (str (System/getProperty "java.io.tmpdir") "/dais-targets-test-" (random-uuid))
        st (-> (state/initial-state config)
               (assoc-in [:targets 3] {:type :tmux :pane "x:y.0"})
               (assoc :active-slot 3))]
    (testing "round-trip"
      (state/save-targets! dir st)
      (is (= {:targets (:targets st) :active-slot 3} (state/load-targets dir))))
    (testing "missing file yields nil"
      (is (nil? (state/load-targets (str dir "-nope")))))
    (testing "corrupt or inconsistent file yields nil (config defaults win)"
      (spit (str dir "/targets.edn") "{:garbage")
      (is (nil? (state/load-targets dir)))
      (spit (str dir "/targets.edn") (pr-str {:targets {1 {:type :focus}} :active-slot 9}))
      (is (nil? (state/load-targets dir))))))

(deftest mute-and-dry-run-transitions
  (let [st (state/initial-state config)]
    (is (false? (:muted st)))
    (is (true? (:muted (:state (state/mute st)))))
    (is (false? (:muted (:state (state/unmute (assoc st :muted true))))))
    (testing "dry-run toggles from its seeded value"
      (is (true? (:dry-run (:state (state/toggle-dry-run (assoc st :dry-run false))))))
      (is (false? (:dry-run (:state (state/toggle-dry-run (assoc st :dry-run true)))))))))

(deftest language
  (let [st (state/initial-state config)]
    (testing "defaults to en with no :asr in config"
      (is (= "en" (:language st)))
      (is (= ["en"] (:languages st))))
    (testing "seeded from :asr"
      (let [st2 (state/initial-state (assoc config :asr {:language "fi" :languages ["en" "fi"]}))]
        (is (= "fi" (:language st2)))
        (is (= ["en" "fi"] (:languages st2)))))
    (testing "set-language validates against :languages plus always-allowed auto"
      (let [st2 (assoc st :languages ["en" "fi"])]
        (is (= "fi" (:language (:state (state/set-language st2 "fi")))))
        (is (= "auto" (:language (:state (state/set-language st2 "auto")))))
        (is (some? (:error (state/set-language st2 "de"))))))
    (testing "write-files! emits the language"
      (let [dir (str (System/getProperty "java.io.tmpdir") "/dais-lang-" (random-uuid))]
        (state/write-files! dir (assoc st :language "fi"))
        (is (re-find #"\"language\":\"fi\"" (slurp (str dir "/state.json"))))))))

(deftest state-files
  (let [dir (str (System/getProperty "java.io.tmpdir") "/dais-state-test-" (random-uuid))
        st (assoc (state/initial-state config) :mode :vad-listening :speech true
                  :muted true :dry-run true)]
    (state/write-files! dir st)
    (is (.exists (java.io.File. dir "state.json")))
    (is (.exists (java.io.File. dir "mic-recording")))
    (is (.exists (java.io.File. dir "speech-detected")))
    (is (.exists (java.io.File. dir "muted")))
    (is (.exists (java.io.File. dir "dry-run")))
    (state/write-files! dir (state/initial-state config))
    (is (not (.exists (java.io.File. dir "mic-recording"))))
    (is (not (.exists (java.io.File. dir "muted"))))
    (state/cleanup-files! dir)
    (is (not (.exists (java.io.File. dir "dry-run"))))))
