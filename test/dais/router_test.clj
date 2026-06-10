(ns dais.router-test
  (:require [clojure.test :refer [deftest is testing]]
            [dais.router :as router]))

(def commands (router/merged-commands {}))

(defn- route [text & {:as opts}]
  (router/route text (merge {:commands commands} opts)))

(deftest keypress-grammar
  (testing "simple key presses"
    (is (= {:action :press-keys :keys ["Enter"]} (route "press enter")))
    (is (= {:action :press-keys :keys ["Enter"]} (route "Press Enter.")))
    (is (= {:action :press-keys :keys ["Escape"]} (route "hit escape"))))
  (testing "option selection with fillers and ordinals"
    (is (= {:action :press-keys :keys ["1"]} (route "select one")))
    (is (= {:action :press-keys :keys ["2"]} (route "choose option two")))
    (is (= {:action :press-keys :keys ["2"]} (route "choose the second option")))
    (is (= {:action :press-keys :keys ["3"]} (route "answer 3"))))
  (testing "compound sequences"
    (is (= {:action :press-keys :keys ["Down" "Enter"]}
           (route "press down and enter")))
    (is (= {:action :press-keys :keys ["Down" "Down" "Enter"]}
           (route "press down down then enter"))))
  (testing "control chords"
    (is (= {:action :press-keys :keys ["C-a"]} (route "press control a")))
    (is (= {:action :press-keys :keys ["C-k"]} (route "press control k")))
    (is (= {:action :press-keys :keys ["C-w"]} (route "hit ctrl w")))
    (is (= {:action :press-keys :keys ["C-a" "C-k"]}
           (route "press control a then control k")))
    (testing "unpaired control falls through to dictation"
      (is (= :type-text (:action (route "press control to the tower"))))))
  (testing "every-token rule: trigger verb alone does not make a command"
    (is (= :type-text (:action (route "select the right abstraction for this"))))
    (is (= :type-text (:action (route "press on with the refactoring"))))))

(deftest built-in-commands
  (is (= {:action :press-keys :keys ["C-u"]} (route "scratch that")))
  (is (= {:action :press-keys :keys ["Escape"]} (route "cancel")))
  (is (= {:action :type-text :text "yes" :submit true} (route "yes"))))

(deftest control-commands
  (is (= {:action :control :control :voice-off} (route "voice off")))
  (is (= {:action :control :control :next-target} (route "next target")))
  (is (= {:action :control :control :set-slot :slot 2} (route "target two")))
  (testing "voice off works in every strategy"
    (is (= :control (:action (route "voice off" :strategy :prefix))))
    (is (= :control (:action (route "voice off" :strategy :key-armed))))))

(deftest dictation-and-enter-modes
  (testing "default: type only, no submit, single line"
    (is (= {:action :type-text :text "please review PR #123" :submit false}
           (route "please review\n PR  #123"))))
  (testing "enter-always"
    (is (true? (:submit (route "hello" :enter-mode :enter-always)))))
  (testing "enter-auto strips a trailing spoken enter"
    (is (= {:action :type-text :text "run the tests" :submit true}
           (route "run the tests, enter." :enter-mode :enter-auto)))
    (is (= {:action :type-text :text "" :submit true}
           (route "Enter." :enter-mode :enter-auto)))
    (is (false? (:submit (route "run the tests" :enter-mode :enter-auto))))
    (testing "the word enter mid-sentence does not submit"
      (is (false? (:submit (route "press enter when done" :enter-mode :enter-auto)))))))

(deftest armed-utterances
  (testing "armed: commands work, dictation is refused"
    (is (= :press-keys (:action (route "press enter" :armed true))))
    (is (= {:action :none :reason "armed: not a recognized command"}
           (route "hello there" :armed true)))))

(deftest prefix-strategy
  (testing "prefixed commands"
    (is (= {:action :press-keys :keys ["Enter"]}
           (route "do press enter" :strategy :prefix)))
    (is (= :none (:action (route "do something weird" :strategy :prefix)))))
  (testing "unprefixed is dictation, even command-looking text"
    (is (= :type-text (:action (route "press enter" :strategy :prefix))))))

(deftest key-armed-strategy
  (is (= :type-text (:action (route "press enter" :strategy :key-armed))))
  (is (= :press-keys (:action (route "press enter" :strategy :key-armed :armed true)))))

(deftest empty-transcripts
  (is (= :none (:action (route ""))))
  (is (= :none (:action (route "  ...  ")))))
