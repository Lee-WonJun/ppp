(ns ppp.client.transport-test
  (:require [cljs.test :refer-macros [deftest is]]
            [ppp.client.transport :as transport]
            [ppp.shared.protocol :as protocol]))

(deftest wire-envelope-accepts-session-strings-and-transit-uuids
  (let [session-id (random-uuid)
        request-id (random-uuid)
        fields [request-id 1 :runtime/staged
                {:tab-id (random-uuid)
                 :transaction-id (random-uuid)
                 :base-version 0
                 :target-version 1}]
        from-uuid (apply transport/envelope session-id fields)
        from-string (apply transport/envelope (str session-id) fields)]
    (is (= session-id (:session-id from-uuid)))
    (is (= session-id (:session-id from-string)))
    (is (protocol/valid-client-envelope? from-uuid))
    (is (protocol/valid-client-envelope? from-string))))
