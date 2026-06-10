(ns dais.executor-test
  (:require [clojure.test :refer [deftest is testing]]
            [dais.executor :as executor]))

(def config
  {:tmux ["tmux" "-L" "dais-test-never-running"]
   :focus {:ydotool ["/usr/bin/ydotool"] :ydotool-socket "/tmp/nope.sock" :key-delay-ms 2}})

(def tmux-target {:type :tmux :pane "app:1.2"})
(def focus-target {:type :focus})

(defn- dry [plan target]
  (executor/execute plan target config {:dry-run true}))

(defn- would-run [result]
  (get-in result ["details" "would_run"]))

(deftest tmux-type-text-plan
  (let [res (dry {:action :type-text :text "hello world" :submit false} tmux-target)
        steps (would-run res)]
    (is (= "ok" (get res "result")))
    (is (true? (get res "dry_run")))
    (is (= 2 (count steps)))
    (testing "text travels on stdin to load-buffer, never argv"
      (is (= "hello world" (get (first steps) "stdin")))
      (is (= "load-buffer" (nth (get (first steps) "argv") 3)))
      (is (not-any? #(= "hello world" %) (get (first steps) "argv"))))
    (testing "paste is bracketed and targeted"
      (let [paste (get (second steps) "argv")]
        (is (some #{"paste-buffer"} paste))
        (is (some #{"-p"} paste))
        (is (some #{"app:1.2"} paste))))))

(deftest tmux-submit-appends-enter
  (let [steps (would-run (dry {:action :type-text :text "hi" :submit true} tmux-target))]
    (is (= 3 (count steps)))
    (is (= ["send-keys" "-t" "app:1.2" "Enter"]
           (drop 3 (get (last steps) "argv")))))
  (testing "empty text with submit is just Enter (enter-auto bare \"enter\")"
    (let [steps (would-run (dry {:action :type-text :text "" :submit true} tmux-target))]
      (is (= 1 (count steps))))))

(deftest tmux-press-keys
  (let [steps (would-run (dry {:action :press-keys :keys ["Down" "Enter"]} tmux-target))]
    (is (= ["send-keys" "-t" "app:1.2" "Down" "Enter"]
           (drop 3 (get (first steps) "argv"))))))

(deftest key-whitelist-enforced
  (let [res (dry {:action :press-keys :keys ["Enter" "x"]} tmux-target)]
    (is (= "error" (get res "result"))))
  (let [res (dry {:action :press-keys :keys []} focus-target)]
    (is (= "error" (get res "result")))))

(deftest focus-type-text-plan
  (let [steps (would-run (dry {:action :type-text :text "hello" :submit true} focus-target))]
    (is (= 2 (count steps)))
    (testing "text on stdin to ydotool type --file -"
      (is (= "hello" (get (first steps) "stdin")))
      (is (= ["/usr/bin/ydotool" "type" "--key-delay" "2" "--file" "-"]
             (get (first steps) "argv"))))
    (testing "submit presses Enter by keycode"
      (is (= ["/usr/bin/ydotool" "key" "28:1" "28:0"]
             (get (second steps) "argv"))))))

(deftest focus-chords-press-then-release-reversed
  (let [steps (would-run (dry {:action :press-keys :keys ["C-c"]} focus-target))]
    (is (= ["/usr/bin/ydotool" "key" "29:1" "46:1" "46:0" "29:0"]
           (get (first steps) "argv")))))

(deftest no-target
  (let [res (executor/execute {:action :press-keys :keys ["Enter"]} nil config {:dry-run true})]
    (is (= "error" (get res "result")))))

(deftest live-tmux-missing-pane-fails-safe
  ;; Uses a scratch socket name that has no server: list-panes exits non-zero,
  ;; so the send must fail before any tmux mutation is attempted.
  (let [res (executor/execute {:action :press-keys :keys ["Enter"]}
                              tmux-target config {:dry-run false})]
    (is (= "error" (get res "result")))
    (is (re-find #"not found" (get res "error")))))
