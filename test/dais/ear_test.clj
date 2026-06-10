(ns dais.ear-test
  (:require [clojure.test :refer [deftest is testing]]
            [dais.ear :as ear]))

(deftest mode-transitions-to-ear-messages
  (testing "latch lifecycle"
    (is (= {"type" "latch_start"}
           (ear/ear-message :off :manual-recording "toggle-record")))
    (is (= {"type" "latch_stop"}
           (ear/ear-message :manual-recording :off "toggle-record"))))
  (testing "voice-off during a latch DISCARDS instead of transcribing"
    (is (= {"type" "set_mode" "mode" "off"}
           (ear/ear-message :manual-recording :off "voice-off")))
    (is (= {"type" "set_mode" "mode" "off"}
           (ear/ear-message :manual-recording :off "voice:voice-off"))))
  (testing "vad session"
    (is (= {"type" "set_mode" "mode" "vad"}
           (ear/ear-message :off :vad-listening "toggle-vad")))
    (is (= {"type" "set_mode" "mode" "off"}
           (ear/ear-message :vad-listening :off "toggle-vad"))))
  (testing "no message when mode does not change (slot switches, arm)"
    (is (nil? (ear/ear-message :off :off "target-use:2")))
    (is (nil? (ear/ear-message :vad-listening :vad-listening "arm")))))
