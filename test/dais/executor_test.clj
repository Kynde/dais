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

(deftest key-vocabulary-enforced
  (testing "an unknown base key is rejected"
    (is (= "error" (get (dry {:action :press-keys :keys ["Enter" "Nope"]} tmux-target) "result"))))
  (testing "an unknown modifier prefix is rejected"
    (is (= "error" (get (dry {:action :press-keys :keys ["X-a"]} tmux-target) "result"))))
  (testing "empty key list is rejected"
    (is (= "error" (get (dry {:action :press-keys :keys []} focus-target) "result")))))

(deftest chord-parsing
  (testing "modifiers + base parse; order-insensitive set of mods"
    (is (= {:mods #{"C" "M"} :base "t"} (executor/parse-chord "C-M-t")))
    (is (= {:mods #{} :base "Enter"} (executor/parse-chord "Enter")))
    (is (= {:mods #{"C"} :base "a"} (executor/parse-chord "C-a"))))
  (testing "bare letters/digits are now valid keys"
    (is (executor/valid-chord? "x"))
    (is (executor/valid-chord? "1")))
  (testing "unknown base / modifier are invalid"
    (is (not (executor/valid-chord? "Nope")))
    (is (not (executor/valid-chord? "X-a")))
    (is (not (executor/valid-chord? "C-")))))

(deftest chord-renders-both-backends
  (testing "tmux token is the canonical chord string"
    (let [steps (would-run (dry {:action :press-keys :keys ["C-M-t" "M-Right"]} tmux-target))]
      (is (= ["send-keys" "-t" "app:1.2" "C-M-t" "M-Right"]
             (drop 3 (get (first steps) "argv"))))))
  (testing "PgUp maps to the tmux PageUp name"
    (let [steps (would-run (dry {:action :press-keys :keys ["PgUp"]} tmux-target))]
      (is (= "PageUp" (last (get (first steps) "argv"))))))
  (testing "ydotool presses modifiers in order, releases in reverse (ctrl-alt-t)"
    (let [steps (would-run (dry {:action :press-keys :keys ["C-M-t"]} focus-target))]
      (is (= ["/usr/bin/ydotool" "key" "29:1" "56:1" "20:1" "20:0" "56:0" "29:0"]
             (get (first steps) "argv"))))))

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
           (get (first steps) "argv"))))
  (testing "line-editing chords"
    (let [steps (would-run (dry {:action :press-keys :keys ["C-a" "C-k"]} focus-target))]
      (is (= ["/usr/bin/ydotool" "key"
              "29:1" "30:1" "30:0" "29:0"
              "29:1" "37:1" "37:0" "29:0"]
             (get (first steps) "argv"))))
    (is (= "ok" (get (dry {:action :press-keys :keys ["C-w"]} tmux-target) "result")))))

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
