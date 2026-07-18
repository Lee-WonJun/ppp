(ns ppp.client.progress-test
  (:require [cljs.test :refer [deftest is testing]]
            [ppp.client.progress :as progress]))

(deftest every-public-phase-has-one-bounded-presentation
  (doseq [[phase expected]
          [[:generating ["Generating" "Thinking through your request"]]
           [:validating ["Validating" "Checking the proposed product"]]
           [:applying ["Applying" "Updating the live product"]]
           [:applied ["Applied" "Your product is ready"]]]]
    (let [{:keys [label detail] :as presentation}
          (progress/presentation phase)]
      (is (= expected [label detail]))
      (is (<= (count (progress/accessible-label presentation)) 80)))))

(deftest unknown-phases-and-queue-copy-stay-bounded
  (testing "unknown protocol input does not become user-visible detail"
    (is (= {:label "Working" :detail "Keeping your product moving"}
           (progress/presentation :unexpected-internal-stage))))
  (testing "an explicit bounded queue detail can override the phase default"
    (is (= {:label "Generating" :detail "Waiting for the current request"}
           (progress/presentation :generating
                                  "Waiting for the current request")))))

(deftest provider-progress-uses-only-the-shared-allowlist
  (is (= {:label "Generating" :detail "Shaping a product direction"}
         (progress/presentation :generating
                                "Shaping a product direction")))
  (is (= {:label "Generating" :detail "Thinking through your request"}
         (progress/presentation :generating
                                "src/private.cljs password=secret")))
  (is (= {:label "Validating" :detail "Checking the proposed product"}
         (progress/presentation :validating
                                "Shaping a product direction"))))
