(ns dais.event-test
  (:require [clojure.test :refer [deftest is testing]]
            [dais.event :as event]))

(deftest make-and-validate
  (let [ev (event/make-event {:type "voice.transcript"
                              :payload {"text" "hello"}})]
    (is (event/valid-envelope? ev))
    (is (= "dais.event.v1" (get ev "schema")))
    (is (seq (get ev "id")))
    (is (seq (get ev "time"))))
  (testing "round-trips through JSON"
    (let [ev (event/make-event {:type "action.executed" :payload {"x" 1}
                                :trace-id "t-1" :parent-id "p-1"})]
      (is (= ev (event/parse-json-line (event/->json-line ev)))))))

(deftest invalid-envelopes
  (is (not (event/valid-envelope? nil)))
  (is (not (event/valid-envelope? {})))
  (is (not (event/valid-envelope? {"schema" "dais.event.v1" "id" "x" "time" "t"
                                   "type" "notdotted" "source" {"module" "m"}
                                   "payload" {}})))
  (is (not (event/valid-envelope? (dissoc (event/make-event {:type "a.b" :payload {}})
                                          "source")))))

(deftest required-args
  (is (thrown? Exception (event/make-event {:payload {}})))
  (is (thrown? Exception (event/make-event {:type "a.b"}))))
