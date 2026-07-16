(ns ppp.client.composer-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ppp.client.composer :as composer]))

(deftest composer-key-action-contract
  (testing "plain Enter sends one nonblank idle draft"
    (is (= :send
           (composer/key-action
            {:key "Enter" :draft "hello"}))))

  (testing "Shift+Enter and composition confirmation remain text input"
    (is (= :pass
           (composer/key-action
            {:key "Enter" :shift? true :draft "hello"})))
    (is (= :pass
           (composer/key-action
            {:key "Enter" :composing? true :draft "안녕"}))))

  (testing "blank and busy drafts do not submit"
    (is (= :ignore
           (composer/key-action
            {:key "Enter" :draft "  \n"})))
    (is (= :ignore
           (composer/key-action
            {:key "Enter" :busy? true :draft "hello"}))))

  (testing "other or already-consumed keys pass through"
    (is (= :pass
           (composer/key-action
            {:key "ArrowDown" :draft "hello"})))
    (is (= :pass
           (composer/key-action
            {:key "Enter" :default-prevented? true :draft "hello"})))))

(deftest keyboard-handler-prevents-newline-and-sends-once
  (let [prevented (atom 0)
        sent (atom 0)
        event #js {:key "Enter"
                   :shiftKey false
                   :isComposing false
                   :defaultPrevented false
                   :preventDefault #(swap! prevented inc)}]
    (is (= :send
           (composer/handle-key-down! event false "hello" #(swap! sent inc))))
    (is (= 1 @prevented))
    (is (= 1 @sent))))

(deftest shift-enter-keeps-the-native-newline-path
  (let [prevented (atom 0)
        sent (atom 0)
        event #js {:key "Enter"
                   :shiftKey true
                   :isComposing false
                   :defaultPrevented false
                   :preventDefault #(swap! prevented inc)}]
    (is (= :pass
           (composer/handle-key-down! event false "hello" #(swap! sent inc))))
    (is (zero? @prevented))
    (is (zero? @sent))))

(deftest frame-bridge-recognizes-only-the-semantic-composer
  (let [message-target #js {:tagName "TEXTAREA"
                            :getAttribute #(when (= % "aria-label") "Message")}
        other-target #js {:tagName "TEXTAREA"
                          :getAttribute #(when (= % "aria-label") "Notes")}]
    (is (true? (composer/message-textarea-event? #js {:target message-target})))
    (is (false? (composer/message-textarea-event? #js {:target other-target})))
    (is (false? (composer/message-textarea-event?
                 #js {:target #js {:tagName "INPUT"
                                   :getAttribute (constantly "Message")}})))))
