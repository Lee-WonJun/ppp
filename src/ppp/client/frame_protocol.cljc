(ns ppp.client.frame-protocol
  #?(:cljs (:require [cognitect.transit :as transit])))

(def protocol-version 1)
(def max-wire-characters (* 7 1024 1024))

#?(:cljs (defonce writer (transit/writer :json)))
#?(:cljs (defonce reader (transit/reader :json)))

(def host-message-types
  #{:host/stage
    :host/activate
    :host/sidebar-model
    :host/sidebar-open
    :host/action-result
    :host/action-error
    :host/product-event
    :host/set-state
    :host/dispose})

(def frame-message-types
  #{:frame/ready
    :frame/staged
    :frame/activated
    :frame/rejected
    :frame/state
    :frame/action
    :frame/sidebar-event
    :frame/safe-mode
    :frame/runtime-error
    :frame/diagnostic})

(defn envelope
  [channel type payload]
  {:protocol-version protocol-version
   :channel channel
   :type type
   :payload (or payload {})})

(defn valid-envelope?
  [allowed-types value]
  (and (map? value)
       (= protocol-version (:protocol-version value))
       (string? (:channel value))
       (contains? allowed-types (:type value))
       (map? (:payload value))))

#?(:cljs
   (defn encode
     [value]
     (transit/write writer value)))

#?(:cljs
   (defn decode
     [wire-value]
     (when (and (string? wire-value)
                (<= (count wire-value) max-wire-characters))
       (transit/read reader wire-value))))
