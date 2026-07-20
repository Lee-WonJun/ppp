(ns ppp.client.runtime-test
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [ppp.client.runtime :as runtime]
            [ppp.runtime.policy :as policy]))

(defn- source-map
  ([client]
   (source-map client
               "(ns runtime.sidebar (:require [runtime.api :as api]))\n(api/register-sidebar! (fn [_] [:aside {:aria-label \"Product conversation\"}]))"))
  ([client sidebar]
   {"src/shared/runtime/domain.cljc" "(ns runtime.domain)\n(def compatible? true)"
    "src/client/runtime/client.cljs" client
    "src/client/runtime/sidebar.cljs" sidebar
    "styles/runtime.css" ":host { color: black; }"}))

(defn- page-source
  [label & body]
  (str "(ns runtime.client (:require [runtime.api :as api]))\n"
       (apply str body)
       "\n(api/register-page! :home (fn [_] [:main {:aria-label \""
       label
       "\"} (str (:draft @api/page-state))]))"))

(defn- code
  [thunk]
  (try
    (thunk)
    nil
    (catch :default error
      (loop [current error
             depth 0]
        (when (and current (< depth 16))
          (or (:cause-code (ex-data current))
              (:code (ex-data current))
              (recur (ex-cause current) (inc depth))))))))

(use-fixtures :each
  {:before runtime/reset-runtime!
   :after runtime/reset-runtime!})

(deftest registry-lifecycle-preserves-compatible-state
  (reset! runtime/base-state {:draft "kept" :filter :all})
  (let [first-runtime
        (runtime/stage-source!
         {:version 1
          :source-map
          (source-map
           (page-source "First product"
                        "(swap! api/page-state assoc :hidden-render-leak true)"))})]
    (testing "evaluation is isolated until explicit activation"
      (is (= {:draft "kept" :filter :all} @runtime/base-state))
      (is (= true (:hidden-render-leak @(:state first-runtime))))
      (is (nil? @runtime/active-runtime)))

    (runtime/retain-stage! first-runtime)
    (runtime/activate! 1)

    (testing "activation keeps the stable host state and drops stage mutations"
      (is (= 1 (runtime/active-version)))
      (is (= {:draft "kept" :filter :all}
             @(runtime/active-page-state))))

    (swap! (runtime/active-page-state) assoc :filter :judges)
    (let [second-runtime
          (runtime/stage-source!
           {:version 2
            :source-map (source-map (page-source "Second product"))})]
      (runtime/retain-stage! second-runtime)
      (runtime/activate! 2)
      (testing "compatible state survives component replacement"
        (is (= 2 (runtime/active-version)))
        (is (= {:draft "kept" :filter :judges}
               @(runtime/active-page-state)))))))

(deftest browser-repl-redefines-the-active-page-without-resetting-state
  (reset! runtime/base-state {:draft "kept" :ticks 3})
  (let [staged (runtime/stage-source!
                {:version 1
                 :source-map (source-map (page-source "Before REPL"))})]
    (runtime/retain-stage! staged)
    (runtime/activate! 1)
    (is (= "Before REPL"
           (get-in ((:page @runtime/active-runtime) {}) [1 :aria-label])))

    (let [result
          (runtime/eval-live!
           (str "(ns runtime.client (:require [runtime.api :as api]))\n"
                "(defn repl-page [_]"
                " [:main {:aria-label \"After REPL\"}"
                "  (str (:draft @api/page-state) \"/\" (:ticks @api/page-state))])\n"
                "(api/register-page! :home repl-page)"))]
      (is (= 1 (:runtime-version result)))
      (is (:page? result)))
    (is (= {:draft "kept" :ticks 3} @(runtime/active-page-state)))
    (is (= "After REPL"
           (get-in ((:page @runtime/active-runtime) {}) [1 :aria-label])))
    (is (= "kept/3" (get-in ((:page @runtime/active-runtime) {}) [2])))

    (is (= :repl/client-eval-failed
           (code #(runtime/eval-live! "(defn broken ["))))
    (is (= "After REPL"
           (get-in ((:page @runtime/active-runtime) {}) [1 :aria-label])))))

(deftest browser-repl-branch-remains-hidden-until-durable-activation
  (reset! runtime/base-state {:draft "kept"})
  (let [active (runtime/stage-source!
                {:version 1
                 :source-map (source-map (page-source "Visible"))})]
    (runtime/retain-stage! active)
    (runtime/activate! 1)
    (let [branch (runtime/stage-source!
                  {:version 2
                   :source-map (source-map (page-source "Candidate"))})
          {:keys [runtime result]}
          (runtime/eval-runtime!
           branch
           (str "(ns runtime.client (:require [runtime.api :as api]))\n"
                "(defn repaired [_] [:main {:aria-label \"Repaired candidate\"}"
                " (:draft @api/page-state)])\n"
                "(api/register-page! :home repaired)"))]
      (runtime/retain-evaluated! runtime)
      (is (= 2 (:runtime-version result)))
      (is (= "Visible"
             (get-in ((:page @runtime/active-runtime) {}) [1 :aria-label])))
      (is (= {:draft "kept"} @(runtime/active-page-state)))
      (runtime/activate! 2)
      (is (= "Repaired candidate"
             (get-in ((:page @runtime/active-runtime) {}) [1 :aria-label])))
      (is (= "kept" (get-in ((:page @runtime/active-runtime) {}) [2]))))))

(deftest declared-state-defaults-apply-only-after-activation-and-never-overwrite-users
  (reset! runtime/base-state {:account/mode :login :existing true})
  (let [staged
        (runtime/stage-source!
         {:version 1
          :source-map
          (source-map
           (page-source
            "Account product"
            "(api/initialize-state! {:account/mode :signup :account/name \"\"})"
            "(swap! api/page-state assoc :staging-only true)"))})]
    (is (= {:account/mode :login :existing true} @runtime/base-state))
    (is (= "" (:account/name @(:state staged))))
    (is (= true (:staging-only @(:state staged))))
    (runtime/retain-stage! staged)
    (runtime/activate! 1)
    (is (= {:account/mode :login
            :account/name ""
            :existing true}
           @(runtime/active-page-state)))))

(deftest failed-stage-cannot-change-active-runtime
  (let [first-runtime
        (runtime/stage-source!
         {:version 1
          :source-map (source-map (page-source "Working product"))})]
    (runtime/retain-stage! first-runtime)
    (runtime/activate! 1)
    (swap! (runtime/active-page-state) assoc :draft "untouched")

    (is (= :runtime/client-registration-contract
           (code #(runtime/stage-source!
                   {:version 2
                    :source-map
                    (source-map
                     "(ns runtime.client (:require [runtime.api :as api]))")}))))
    (is (= 1 (runtime/active-version)))
    (is (= "untouched" (:draft @(runtime/active-page-state))))
    (is (= "Working product"
           (get-in ((:page @runtime/active-runtime) {}) [1 :aria-label])))))

(deftest safe-mode-stage-does-not-evaluate-generated-sidebar
  (let [staged
        (runtime/stage-source!
         {:version 1
          :sidebar-enabled? false
          :source-map
          (source-map
           (page-source "Safe product")
           "(ns runtime.sidebar)\n(throw (js/Error. \"sidebar side effect\"))")})]
    (is (= "Safe product"
           (get-in ((:page staged) {}) [1 :aria-label])))
    (is (nil? ((:sidebar staged) {})))))

(deftest ensured-action-data-refreshes-on-each-runtime-version
  (async done
         (let [calls (atom [])
               client
               (str "(ns runtime.client (:require [runtime.api :as api]))\n"
                    "(defn page [_]\n"
                    "  (api/ensure-action! :rules/current {} :rules/data)\n"
                    "  [:main (str (:weight (:rules/data @api/page-state)))])\n"
                    "(api/register-page! :home page)")
               activate-with-weight!
               (fn [version weight]
                 (let [staged
                       (runtime/stage-source!
                        {:version version
                         :source-map (source-map client)
                         :action-fn
                         (fn [action-id payload]
                           (swap! calls conj [version action-id payload])
                           (js/Promise.resolve {:result {:weight weight}}))})]
                   (runtime/retain-stage! staged)
                   (runtime/activate! version)
                   ((:page staged) {})
                   ((:page staged) {})))]
           (activate-with-weight! 1 1)
           (-> (js/Promise.resolve nil)
               (.then
                (fn []
                  (is (= 1 (:weight (:rules/data @(runtime/active-page-state)))))
                  (is (= 1 (count @calls)) "ensure runs once within one runtime")
                  (activate-with-weight! 2 3)
                  (js/Promise.resolve nil)))
               (.then
                (fn []
                  (is (= 3 (:weight (:rules/data @(runtime/active-page-state)))))
                  (is (= 2 (count @calls)) "a new runtime refreshes derived data")
                  (done)))
               (.catch
                (fn [error]
                  (is false (str "unexpected promise rejection: " error))
                  (done)))))))

(deftest registration-and-version-contracts
  (testing "exactly one home page and one sidebar are required"
    (let [wrong-page
          "(ns runtime.client (:require [runtime.api :as api]))\n(api/register-page! :other (fn [_] [:main]))"
          duplicate-page
          (str "(ns runtime.client (:require [runtime.api :as api]))\n"
               "(api/register-page! :home (fn [_] [:main]))\n"
               "(api/register-page! :home (fn [_] [:main]))")]
      (is (= :runtime/client-registration-contract
             (code #(runtime/stage-source!
                     {:version 1 :source-map (source-map wrong-page)}))))
      (is (= :runtime/client-duplicate-page
             (code #(runtime/stage-source!
                     {:version 1 :source-map (source-map duplicate-page)}))))))
  (testing "staged and active versions cannot be reused"
    (let [staged (runtime/stage-source!
                  {:version 1
                   :source-map (source-map (page-source "One"))})]
      (runtime/retain-stage! staged)
      (is (= :runtime/client-stage-duplicate
             (code #(runtime/retain-stage! staged))))
      (runtime/activate! 1)
      (let [stale (runtime/stage-source!
                   {:version 1
                    :source-map (source-map (page-source "Stale"))})]
        (is (= :runtime/client-stage-stale
               (code #(runtime/retain-stage! stale))))))))

(deftest generated-client-capability-boundary
  (testing "implemented capabilities match the single catalog"
    (is (= (set (map name (get-in policy/capability-catalog
                                  [:client :namespaces 'runtime.api])))
           (set (:runtime.api (runtime/capability-inventory)))))
    (is (= (set (map name (get-in policy/capability-catalog
                                  [:client :namespaces 'clojure.string])))
           (set (:clojure.string (runtime/capability-inventory))))))

  (testing "dynamic code loading and host-runtime mutation remain unavailable"
    (doseq [form ["(eval '(+ 1 1))"
                  "(load-string \"(+ 1 1)\")"
                  "(alter-var-root #'map identity)"]]
      (let [client (str "(ns runtime.client (:require [runtime.api :as api]))\n"
                        form
                        "\n(api/register-page! :home (fn [_] [:main]))")]
        (is (some? (code #(runtime/stage-source!
                           {:version 7
                            :source-map (source-map client)})))
            form))))

  (testing "ordinary JavaScript instance interop is not capability-gated"
    (let [client
          (str "(ns runtime.client (:require [runtime.api :as api]))\n"
               "(def interop-value (aget #js {:value 42} \"value\"))\n"
               "(api/register-page! :home (fn [_] [:main (str interop-value)]))")
          staged (runtime/stage-source!
                  {:version 7 :source-map (source-map client)})]
      (is (= "42" (get-in ((:page staged) {}) [1])))
      (runtime/reject-runtime! staged))))

(deftest generated-product-state-distinguishes-durable-and-local-values
  (testing "a generated client may keep disposable frame-local implementation state"
    (let [client
          (str "(ns runtime.client (:require [runtime.api :as api]))\n"
               "(def game-state (atom {:x 0}))\n"
               "(api/register-page! :home\n"
               " (fn [_] [:main [:output (str (:x @game-state))]\n"
               "          [:button {:on-click #(swap! game-state update :x inc)} \"Move\"]]))")
          staged (runtime/stage-source!
                  {:version 8 :source-map (source-map client)})
          before ((:page staged) {})]
      (is (= "0" (get-in before [1 1])))
      ((get-in before [2 1 :on-click]) nil)
      (is (= "1" (get-in ((:page staged) {}) [1 1])))
      (runtime/reject-runtime! staged)))

  (testing "replacement-safe reactive product state remains in the bridge atom"
    (let [client
          (str "(ns runtime.client (:require [runtime.api :as api]))\n"
               "(defn page [_]\n"
               "  (let [step (or (:game/step @api/page-state) 0)]\n"
               "    [:main [:output (str step)]\n"
               "     [:button {:on-click #(swap! api/page-state update :game/step (fnil inc 0))}\n"
               "      \"Move\"]]))\n"
               "(api/register-page! :home page)")
          staged (runtime/stage-source!
                  {:version 8 :source-map (source-map client)})]
      (runtime/retain-stage! staged)
      (runtime/activate! 8)
      (let [before ((:page staged) {})
            click (get-in before [2 1 :on-click])]
        (is (= "0" (get-in before [1 1])))
        (is (ifn? click))
        (click nil)
        (is (= 1 (:game/step @(runtime/active-page-state))))
        (is (= "1" (get-in ((:page staged) {}) [1 1])))))))

(deftest generated-automatic-progress-is-bounded-and-lifecycle-managed
  (async done
         (let [client
               (str "(ns runtime.client (:require [runtime.api :as api]))\n"
                    "(api/start-interval! :game/tick 50\n"
                    "  #(swap! api/page-state update :game/ticks (fnil inc 0)))\n"
                    "(defn page [_]\n"
                    "  [:main (str (or (:game/ticks @api/page-state) 0))])\n"
                    "(api/register-page! :home page)")
               staged (runtime/stage-source!
                       {:version 9 :source-map (source-map client)})
               state-key (:state-key staged)]
           (runtime/retain-stage! staged)
           (is (= 1 (count @(:timers staged))))
           (js/setTimeout
            (fn []
              (is (nil? (:game/ticks @(:state staged))))
              (runtime/activate! 9)
              (js/setTimeout
               (fn []
                 (is (pos? (or (:game/ticks @(runtime/active-page-state)) 0)))
                 (runtime/reset-runtime!)
                 (is (empty? @(:timers staged)))
                 (js/setTimeout
                  (fn []
                    (is (not (contains? @runtime/state-root state-key)))
                    (done))
                  90))
               130))
            90))))

(deftest product-events-run-only-in-the-active-runtime-and-use-reactive-state
  (let [errors (atom [])
        client
        (str "(ns runtime.client (:require [runtime.api :as api]))\n"
             "(api/initialize-state! {:events/count 0})\n"
             "(api/register-event-handler! :scores/changed\n"
             "  (fn [{:keys [delta]}]\n"
             "    (swap! api/page-state update :events/count + delta)))\n"
             "(api/register-page! :home\n"
             "  (fn [_] [:main [:output (str (:events/count @api/page-state))]]))")
        staged (runtime/stage-source! {:version 14
                                       :source-map (source-map client)
                                       :runtime-error-fn #(swap! errors conj %)})]
    (is (nil? (runtime/deliver-event! :scores/changed {:delta 2})))
    (runtime/retain-stage! staged)
    (runtime/activate! 14)
    (is (true? (runtime/deliver-event! :scores/changed {:delta 2})))
    (is (= 2 (:events/count @(runtime/active-page-state))))
    (is (= "2" (get-in ((:page @runtime/active-runtime) {}) [1 1])))
    (is (nil? (runtime/deliver-event! :unknown {})))
    (is (empty? @errors))))

(deftest generated-interval-policy-rejects-unsafe-schedules
  (let [client
        (str "(ns runtime.client (:require [runtime.api :as api]))\n"
             "(defn page [_]\n"
             "  (api/start-interval! :game/tick 1 #(swap! api/page-state assoc :tick true))\n"
             "  [:main])\n"
             "(api/register-page! :home page)")
        staged (runtime/stage-source!
                {:version 10 :source-map (source-map client)})]
    (is (= :runtime/client-interval-range
           (code #((:page staged) {}))))
    (runtime/reject-runtime! staged)))

(deftest generated-view-and-style-use-the-sandboxed-browser-platform
  (testing "ordinary frontend elements, refs, resources, and custom elements are accepted"
    (doseq [view ["[:iframe {:src \"http://127.0.0.1/\"}]"
                  "[:img {:src \"https://example.com/a.png\"}]"
                  "[:a {:href \"https://example.com\"} \"leave\"]"
                  "[:form {:action \"https://example.com\"}]"
                  "[:div {:dangerouslySetInnerHTML {:__html \"unsafe\"}}]"
                  "[:custom-widget]"
                  "[:div {:style {:backgroundImage \"url(https://example.com/x)\"}}]"]]
      (let [client (str "(ns runtime.client (:require [runtime.api :as api]))\n"
                        "(api/register-page! :home (fn [_] " view "))")
            staged (runtime/stage-source!
                    {:version 9 :source-map (source-map client)})]
        (is (vector? ((:page staged) {})) view)
        (runtime/reject-runtime! staged))))

  (testing "frame CSS supports the same resource syntax as ordinary frontend CSS"
    (doseq [css ["@import 'https://example.com/x.css';"
                 ".x { background: url(https://example.com/x); }"
                 ".x { background: image-set('https://example.com/x' 1x); }"
                 ".x { background: u\\72l(https://example.com/x); }"]]
      (let [source (assoc (source-map (page-source "Safe"))
                          "styles/runtime.css" css)
            staged (runtime/stage-source! {:version 9 :source-map source})]
        (is (= css (:css staged)))
        (runtime/reject-runtime! staged))))

  (testing "ordinary semantic product UI remains valid"
    (let [client
          (str "(ns runtime.client (:require [runtime.api :as api]))\n"
               "(api/register-page! :home"
               " (fn [_] [:main [:nav [:button {:on-click (fn [_] :ok)} \"Open\"]]"
               "          [:form [:label \"Name\" [:input {:required true}]]"
               "                 [:button {:type \"submit\"} \"Save\"]]]))")
          staged (runtime/stage-source! {:version 10 :source-map (source-map client)})]
      (is (= :main (first ((:page staged) {}))))
      (runtime/reject-runtime! staged))))

(deftest generated-child-collections-preserve-hiccup-semantics
  (let [client
        (str "(ns runtime.client (:require [runtime.api :as api]))\n"
             "(api/register-page! :home"
             " (fn [_] [:main (for [label [\"A\" \"B\"]]"
             "                  ^{:key label} [:span label])]))")
        staged (runtime/stage-source! {:version 13 :source-map (source-map client)})
        children (second ((:page staged) {}))]
    (is (sequential? children))
    (is (not (vector? children)) "A child vector would be parsed as a component call")
    (is (= [[:span "A"] [:span "B"]] (vec children)))
    (runtime/reject-runtime! staged)))

(deftest generated-execution-and-view-budgets
  (testing "an infinite interpreted loop is interrupted during staging"
    (let [client
          (str "(ns runtime.client (:require [runtime.api :as api]))\n"
               "(loop [] (recur))\n"
               "(api/register-page! :home (fn [_] [:main]))")]
      (is (= :runtime/client-timeout
             (code #(runtime/stage-source!
                     {:version 11 :source-map (source-map client)}))))))

  (testing "an unbounded lazy render tree is capped before React sees it"
    (let [client
          (str "(ns runtime.client (:require [runtime.api :as api]))\n"
               "(api/register-page! :home (fn [_] [:main (repeat [:span \"x\"])]))")
          staged (runtime/stage-source! {:version 12 :source-map (source-map client)})]
      (is (= :runtime/client-view-size
             (code #((:page staged) {}))))
      (runtime/reject-runtime! staged))))
