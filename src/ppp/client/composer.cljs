(ns ppp.client.composer
  (:require [clojure.string :as str]))

(defn key-action
  [{:keys [key shift? composing? busy? draft default-prevented?]}]
  (cond
    (or (not= "Enter" key) shift? composing? default-prevented?) :pass
    (or busy? (str/blank? (str draft))) :ignore
    :else :send))

(defn message-textarea-event?
  [event]
  (let [target (.-target event)]
    (and target
         (= "TEXTAREA" (.-tagName target))
         (= "Message" (.getAttribute target "aria-label")))))

(defn event-key-action
  [event busy? draft]
  (let [native-event (.-nativeEvent ^js event)]
    (key-action
     {:key (.-key event)
      :shift? (boolean (.-shiftKey event))
      :composing? (boolean
                   (or (.-isComposing event)
                       (when native-event
                         (.-isComposing ^js native-event))
                       (= 229 (.-keyCode event))))
      :busy? busy?
      :draft draft
      :default-prevented? (boolean (.-defaultPrevented event))})))

(defn handle-key-down!
  [event busy? draft send!]
  (let [action (event-key-action event busy? draft)]
    (when-not (= :pass action)
      (.preventDefault event))
    (when (= :send action)
      (send!))
    action))
