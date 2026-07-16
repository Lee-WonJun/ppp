(ns ppp.provider.fake
  (:require [clojure.string :as str]
            [ppp.provider.core :as provider]))

(def ^:private floating-sidebar
  (str
   "(ns runtime.sidebar\n  (:require [runtime.api :as api]))\n\n"
   "(defn message [{:keys [role text status]}]\n"
   "  [:article {:class (str \"runtime-message runtime-message-\" (name role))}\n"
   "   [:p text]\n   (when status [:small status])])\n\n"
   "(defn sidebar [{:keys [sessions session-id messages checkpoints draft busy? progress\n"
   "                              select-session! new-session! restore! draft-change! send!]}]\n"
   "  [:aside.runtime-sidebar.runtime-sidebar-floating {:aria-label \"Product conversation\"}\n"
   "   [:header.runtime-sidebar-header\n"
   "    [:div.runtime-sidebar-title [:strong \"PPP\"] [:span \"Live product workspace\"]]\n"
   "    [:div.runtime-session-tools\n"
   "     [:select {:aria-label \"Current session\"\n"
   "               :value (str session-id)\n"
   "               :on-change #(select-session! (api/event-value %))}\n"
   "      (for [{:keys [id title]} sessions]\n"
   "        ^{:key id} [:option {:value id} title])]\n"
   "     [:button {:type \"button\" :aria-label \"New session\" :on-click new-session!} \"+\"]]]\n"
   "   [:section.runtime-conversation {:aria-live \"polite\"}\n"
   "    (if (seq messages)\n"
   "      (for [item messages] ^{:key (:id item)} [message item])\n"
   "      [:div.runtime-empty\n"
   "       [:strong \"Start with the outcome.\"]\n"
   "       [:p \"Describe a product, a rule, or a visual change.\"]])\n"
   "    (when progress [:p.runtime-progress progress])]\n"
   "   (when (seq checkpoints)\n"
   "     [:section.runtime-checkpoints {:aria-label \"Checkpoints\"}\n"
   "      [:strong \"Checkpoints\"]\n"
   "      (for [{:keys [runtime-version title]} (reverse checkpoints)]\n"
   "        ^{:key runtime-version}\n"
   "        [:button {:type \"button\" :disabled busy?\n"
   "                  :on-click #(restore! runtime-version)}\n"
   "         title])])\n"
   "   [:form.runtime-composer\n"
   "    {:on-submit (fn [event]\n"
   "                  (api/prevent-default! event)\n"
   "                  (when-not busy? (send!)))}\n"
   "    [:textarea {:aria-label \"Message\"\n"
   "                :placeholder \"What should this product do?\"\n"
   "                :value draft\n"
   "                :disabled busy?\n"
   "                :on-change #(draft-change! (api/event-value %))}]\n"
   "    [:button {:type \"submit\" :disabled (or busy? (empty? draft))}\n"
   "     (if busy? \"Working\" \"Send\")]]])\n\n"
   "(api/register-sidebar! sidebar)\n"))

(def ^:private base-workspace-css
  ":host { color: #171714; font-family: \"IBM Plex Sans\", \"Segoe UI\", sans-serif; }\n.runtime-sidebar { pointer-events: auto; position: fixed; z-index: 10; inset: 20px 20px 20px auto; display: grid; grid-template-rows: auto 1fr auto; width: min(420px, calc(100vw - 40px)); min-height: calc(100dvh - 40px); overflow: hidden; border: 1px solid #d9d7ce; border-radius: 18px; background: rgb(251 250 246 / 96%); box-shadow: 0 24px 70px rgb(23 23 20 / 16%); }\n.runtime-sidebar-header { display: grid; gap: 13px; padding: 17px 58px 14px 17px; border-bottom: 1px solid #e5e3dc; }\n.runtime-sidebar-title { display: flex; align-items: baseline; gap: 9px; }\n.runtime-sidebar-title strong { font-size: 15px; letter-spacing: -.02em; }\n.runtime-sidebar-title span { color: #77756d; font-size: 12px; }\n.runtime-session-tools { display: flex; gap: 8px; }\n.runtime-session-tools select { min-width: 0; flex: 1; border: 1px solid #d6d4ca; border-radius: 9px; background: #fff; padding: 9px 10px; }\n.runtime-session-tools button { width: 38px; border: 1px solid #d6d4ca; border-radius: 9px; background: #fff; cursor: pointer; }\n.runtime-conversation { overflow: auto; padding: 22px 18px; }\n.runtime-empty { max-width: 250px; margin: 18vh auto 0; text-align: center; }\n.runtime-empty strong { display: block; margin-bottom: 7px; }\n.runtime-empty p { margin: 0; color: #77756d; line-height: 1.5; }\n.runtime-message { max-width: 340px; margin: 0 0 18px; line-height: 1.5; }\n.runtime-message p { margin: 0; white-space: pre-wrap; }\n.runtime-message-user { margin-left: auto; padding: 11px 13px; border-radius: 12px 12px 3px 12px; background: #eceae2; }\n.runtime-message-assistant { margin-right: auto; }\n.runtime-message small, .runtime-progress { color: #77756d; }\n.runtime-composer { display: grid; grid-template-columns: 1fr auto; gap: 9px; padding: 14px 16px 17px; border-top: 1px solid #e5e3dc; background: #fbfaf6; }\n.runtime-composer textarea { min-height: 72px; resize: none; border: 1px solid #cfcdc3; border-radius: 11px; background: #fff; padding: 11px 12px; color: inherit; }\n.runtime-composer button { align-self: end; min-height: 40px; border: 0; border-radius: 10px; background: #285a43; padding: 0 16px; color: #fff; cursor: pointer; }\n.runtime-composer button:disabled { cursor: default; opacity: .5; }\n.runtime-progress { margin-top: 16px; }\n@media (max-width: 640px) { .runtime-sidebar { inset: 0; width: 100vw; min-height: 100dvh; border: 0; border-radius: 0; } }\n")

(def ^:private workspace-css
  (str/replace
   (str base-workspace-css
        ".runtime-checkpoints { display: grid; gap: 7px; max-height: 150px; overflow: auto; padding: 12px 16px; border-top: 1px solid #e5e3dc; }\n"
        ".runtime-checkpoints strong { color: #77756d; font-size: 12px; }\n"
        ".runtime-checkpoints button { border: 1px solid #d6d4ca; border-radius: 8px; background: #fff; padding: 8px 9px; text-align: left; cursor: pointer; }\n")
   "grid-template-rows: auto 1fr auto"
   "grid-template-rows: auto minmax(0, 1fr) auto auto"))

(def ^:private dark-workspace-css
  (-> workspace-css
      (str/replace "#171714" "#f0f1eb")
      (str/replace "#d9d7ce" "#373a34")
      (str/replace "#e5e3dc" "#343730")
      (str/replace "#d6d4ca" "#4a4e45")
      (str/replace "#cfcdc3" "#4a4e45")
      (str/replace "#eceae2" "#343830")
      (str/replace "#77756d" "#afb2a8")
      (str/replace "#fbfaf6" "#1a1c18")
      (str/replace "#fff" "#252822")
      (str/replace "rgb(251 250 246 / 96%)" "rgb(26 28 24 / 98%)")
      (str/replace "rgb(23 23 20 / 16%)" "rgb(0 0 0 / 38%)")
      (str/replace "#285a43" "#62a47f")
      (str/replace "inset: 20px 20px 20px auto" "inset: 0 0 0 auto")
      (str/replace "min-height: calc(100dvh - 40px)" "min-height: 100dvh")
      (str/replace "border-radius: 18px" "border-radius: 0")))

(def ^:private floating-dark-workspace-css
  (-> dark-workspace-css
      (str/replace "inset: 0 0 0 auto" "inset: 18px 18px 18px auto")
      (str/replace "min-height: 100dvh" "min-height: calc(100dvh - 36px)")
      (str/replace "border-radius: 0" "border-radius: 16px")))

(def ^:private invalid-client-view
  (str
   "(ns runtime.client\n  (:require [runtime.api :as api]))\n\n"
   "(defn page [_context]\n"
   "  (throw (Error. \"Hidden render fixture\")))\n\n"
   "(api/register-page! :home page)\n"))

(def ^:private tetris-client
  (str
   "(ns runtime.client\n  (:require [runtime.api :as api]))\n\n"
   "(def board-width 10)\n"
   "(def board-height 20)\n\n"
   "(when-not (contains? @api/page-state :tetris/x)\n"
   "  (swap! api/page-state assoc :tetris/x 4 :tetris/y 0))\n\n"
   "(defn fall! []\n"
   "  (swap! api/page-state update :tetris/y\n"
   "         (fn [y] (mod (inc (or y 0)) (dec board-height)))))\n\n"
   "(defn move! [delta]\n"
   "  (swap! api/page-state update :tetris/x\n"
   "         (fn [x] (max 0 (min (- board-width 2) (+ (or x 4) delta))))))\n\n"
   "(defn handle-key! [event]\n"
   "  (case (.-key event)\n"
   "    \"ArrowLeft\" (move! -1)\n"
   "    \"ArrowRight\" (move! 1)\n"
   "    \"ArrowDown\" (fall!)\n"
   "    nil))\n\n"
   "(.addEventListener js/window \"keydown\" handle-key!)\n"
   "(def drop-timer (js/setInterval fall! 140))\n\n"
   "(defn page [_]\n"
   "  (let [x (or (:tetris/x @api/page-state) 4)\n"
   "        y (or (:tetris/y @api/page-state) 0)]\n"
   "    [:main.tetris-product {:aria-label \"Tetris game\" :tab-index 0}\n"
   "     [:header [:p \"LIVE PRODUCT\"] [:h1 \"Tetris\"]\n"
   "      [:span \"Arrow keys move the falling piece\"]]\n"
   "     [:section.tetris-stage\n"
   "      [:div.tetris-board {:aria-label \"Tetris board\"}\n"
   "       [:div.tetromino\n"
   "        {:aria-label \"Falling tetromino\"\n"
   "         :style {:transform (str \"translate(\" (* x 24) \"px, \" (* y 24) \"px)\")}}\n"
   "        (for [cell (range 4)]\n"
   "          ^{:key cell} [:span])]]\n"
   "      [:div.tetris-readout\n"
   "       [:span \"Column \" [:output {:aria-label \"Piece column\"} (str x)]]\n"
   "       [:span \"Row \" [:output {:aria-label \"Piece row\"} (str y)]]]]]))\n\n"
   "(api/register-page! :home page)\n"))

(def ^:private tetris-css
  (str
   workspace-css
   ":root { background: #080b12; color: #f3f5f7; }\n"
   ".tetris-product { min-height: 100dvh; display: grid; grid-template-columns: minmax(240px, 1fr) auto; align-items: center; gap: 54px; padding: 48px clamp(32px, 7vw, 112px); background: radial-gradient(circle at 20% 18%, #17243d 0, #080b12 46%); color: #f3f5f7; outline: none; }\n"
   ".tetris-product header { align-self: start; max-width: 480px; }\n"
   ".tetris-product header p { margin: 0 0 14px; color: #6cf0bd; font: 700 12px/1 sans-serif; letter-spacing: .18em; }\n"
   ".tetris-product h1 { margin: 0 0 14px; font: 700 clamp(56px, 9vw, 124px)/.86 sans-serif; letter-spacing: -.07em; }\n"
   ".tetris-product header span { color: #9aa6bd; }\n"
   ".tetris-stage { display: grid; grid-template-columns: 240px auto; align-items: end; gap: 24px; }\n"
   ".tetris-board { position: relative; width: 240px; height: 480px; overflow: hidden; border: 1px solid #35415b; background-color: #0d1320; background-image: linear-gradient(#1b2639 1px, transparent 1px), linear-gradient(90deg, #1b2639 1px, transparent 1px); background-size: 24px 24px; box-shadow: 0 28px 80px rgb(0 0 0 / 45%); }\n"
   ".tetromino { position: absolute; top: 0; left: 0; display: grid; grid-template-columns: repeat(2, 24px); grid-template-rows: repeat(2, 24px); transition: transform 70ms linear; }\n"
   ".tetromino span { margin: 1px; border: 1px solid #b9ffe4; background: #43dca4; box-shadow: inset 0 0 0 3px #76efc1; }\n"
   ".tetris-readout { display: grid; gap: 9px; color: #7f8ba3; font: 600 12px/1.2 monospace; text-transform: uppercase; }\n"
   ".tetris-readout output { color: #f3f5f7; }\n"
   "@media (max-width: 760px) { .tetris-product { grid-template-columns: 1fr; justify-items: center; padding: 80px 20px 32px; } .tetris-product header { justify-self: start; } .tetris-stage { grid-template-columns: 240px; } .tetris-readout { grid-template-columns: 1fr 1fr; } }\n"))

(def ^:private gallery-domain-v1
  "(ns runtime.domain)\n\n(def vote-weights {:public 1 :judge 1})\n\n(defn valid-voter-type? [value]\n  (contains? #{\"public\" \"judge\"} value))\n\n(defn score [votes]\n  (reduce + 0 (map #(get vote-weights % 0) votes)))\n\n(defn ranking-key [{:keys [score created_at id]}]\n  [(- score) created_at id])\n")

(def ^:private gallery-domain-v2
  (str/replace gallery-domain-v1
               "(def vote-weights {:public 1 :judge 1})"
               "(def vote-weights {:public 1 :judge 3})"))

(defn- gallery-domain-test
  [judge-weight require-empty?]
  (str
   "(ns runtime.domain-test\n"
   "  (:require [clojure.test :refer [deftest is testing]]\n"
   "            [runtime.domain :as domain]\n"
   "            [runtime.test :as runtime-test]))\n\n"
   "(defn project [result id]\n"
   "  (first (filter #(= id (:id %)) (:projects result))))\n\n"
   "(deftest voting-business-rules\n"
   "  (let [initial (runtime-test/invoke! :projects/list {})]\n"
   (when require-empty?
     (str
      "    (testing \"new galleries start at zero and ties are deterministic\"\n"
      "      (is (= [1 2 3 4 5 6] (mapv :id (:projects initial))))\n"
      "      (is (every? zero? (map :score (:projects initial)))))\n"))
   "    (testing \"equal scores use created time and id as deterministic ties\"\n"
   "      (is (neg? (compare (domain/ranking-key {:score 4 :created_at \"a\" :id 1})\n"
   "                         (domain/ranking-key {:score 4 :created_at \"a\" :id 2})))))\n"
   "    (testing \"public and judge mutations return the persisted read model\"\n"
   "      (let [public-before (:score (project initial 1))\n"
   "            judge-before (:score (project initial 2))\n"
   "            after-public (runtime-test/invoke! :votes/create\n"
   "                                                {:project-id 1 :voter-type \"public\"})\n"
   "            after-judge (runtime-test/invoke! :votes/create\n"
   "                                               {:project-id 2 :voter-type \"judge\"})\n"
   "            reloaded (runtime-test/invoke! :projects/list {})]\n"
   "        (is (= (inc public-before) (:score (project after-public 1))))\n"
   "        (is (= (+ judge-before " judge-weight ") (:score (project after-judge 2))))\n"
   "        (is (= {:public 1 :judge " judge-weight "} (:weights reloaded)))\n"
   "        (is (= (inc public-before) (:score (project reloaded 1))))\n"
   "        (is (= (+ judge-before " judge-weight ") (:score (project reloaded 2))))))))\n"))

(defn- gallery-server
  [judge-weight]
  (str
   "(ns runtime.server\n"
   "  (:require [runtime.api :as api]\n"
   "            [runtime.domain :as domain]\n"
   "            [clojure.string :as str]))\n\n"
   "(def leaderboard-sql\n"
   "  \"SELECT p.id, p.name, p.tagline, p.created_at,\n"
   "          COALESCE(SUM(CASE WHEN v.voter_type = 'judge' THEN " judge-weight " WHEN v.voter_type = 'public' THEN 1 ELSE 0 END), 0) AS score,\n"
   "          COALESCE(SUM(CASE WHEN v.voter_type = 'judge' THEN 1 ELSE 0 END), 0) AS judge_votes,\n"
   "          COALESCE(SUM(CASE WHEN v.voter_type = 'public' THEN 1 ELSE 0 END), 0) AS public_votes\n"
   "     FROM projects p\n"
   "LEFT JOIN votes v ON v.project_id = p.id\n"
   " GROUP BY p.id\n"
   " ORDER BY score DESC, p.created_at ASC, p.id ASC\")\n\n"
   "(defn list-projects [_request]\n"
   "  {:projects (api/query! leaderboard-sql [])\n"
   "   :weights {:public 1 :judge " judge-weight "}})\n\n"
   "(defn create-vote [{:keys [project-id voter-type]}]\n"
   "  (when-not (and (integer? project-id) (domain/valid-voter-type? voter-type))\n"
   "    (throw (ex-info \"Choose a project and a valid voter type.\" {:code :vote/invalid})))\n"
   "  (api/execute! \"INSERT INTO votes (project_id, voter_type) VALUES (?, ?)\"\n"
   "                [project-id voter-type])\n"
   "  (list-projects nil))\n\n"
   "(defn create-project [{:keys [name tagline]}]\n"
   "  (let [name (str/trim (or name \"\"))\n"
   "        tagline (str/trim (or tagline \"\"))]\n"
   "    (when-not (and (<= 2 (count name) 80) (<= 2 (count tagline) 160))\n"
   "      (throw (ex-info \"Name and tagline are required.\" {:code :project/invalid})))\n"
   "    (api/execute! \"INSERT INTO projects (name, tagline, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)\"\n"
   "                  [name tagline])\n"
   "    (list-projects nil)))\n\n"
   "(api/register-action! :projects/list list-projects)\n"
   "(api/register-action! :projects/create create-project)\n"
   "(api/register-action! :votes/create create-vote)\n"))

(defn- gallery-client
  [podium?]
  (str
   "(ns runtime.client\n  (:require [runtime.api :as api]))\n\n"
   "(def tabs [{:id :gallery :label \"Gallery\"}\n"
   "           {:id :submit :label \"Submit\"}\n"
   "           {:id :leaderboard :label \"Leaderboard\"}])\n\n"
   "(defn refresh! []\n  (api/action! :projects/list {} :gallery/data))\n\n"
   "(defn vote! [project-id]\n"
   "  (api/action! :votes/create\n"
   "               {:project-id project-id\n"
   "                :voter-type (or (:gallery/voter-type @api/page-state) \"public\")}\n"
   "               :gallery/data))\n\n"
   "(defn project-card [{:keys [id name tagline score judge_votes public_votes]} rank]\n"
   "  [:article.project-card\n"
   (if podium?
     "   (when (<= rank 3) [:span.podium-place (str \"#\" rank)])\n"
     "")
   "   [:div [:h2 name] [:p tagline]]\n"
   "   [:footer\n"
   "    [:span (str score \" points\")]\n"
   "    [:span.vote-breakdown (str public_votes \" public, \" judge_votes \" judge\")]\n"
   "    [:button {:type \"button\" :on-click #(vote! id)} \"Vote\"]]])\n\n"
   "(defn navigation [route]\n"
   "  [:nav.product-nav {:aria-label \"Product sections\"}\n"
   "   [:strong \"Open Product Gallery\"]\n"
   "   [:div\n"
   "    (for [{:keys [id label]} tabs]\n"
   "      ^{:key id}\n"
   "      [:button {:type \"button\"\n"
   "                :class (when (= id route) \"active\")\n"
   "                :on-click #(swap! api/page-state assoc :gallery/route id)}\n"
   "       label])]])\n\n"
   "(defn gallery [projects]\n"
   "  [:section.product-section\n"
   "   [:header.section-heading [:p \"Six working ideas\"] [:h1 \"Products you can try, judge, and improve.\"]]\n"
   "   [:label.voter-type \"Vote as\"\n"
   "    [:select {:value (or (:gallery/voter-type @api/page-state) \"public\")\n"
   "              :on-change #(swap! api/page-state assoc :gallery/voter-type (api/event-value %))}\n"
   "     [:option {:value \"public\"} \"Public\"]\n"
   "     [:option {:value \"judge\"} \"Judge\"]]]\n"
   "   [:div.project-grid\n"
   "    (for [[index project] (map-indexed vector projects)]\n"
   "      ^{:key (:id project)} [project-card project (inc index)])]])\n\n"
   "(defn submit []\n"
   "  [:section.product-section.submit-section\n"
   "   [:header.section-heading [:p \"Add a project\"] [:h1 \"Put a working idea in front of the room.\"]]\n"
   "   [:form.submit-form\n"
   "    {:on-submit (fn [event]\n"
   "                  (api/prevent-default! event)\n"
   "                  (api/action! :projects/create\n"
   "                               {:name (:gallery/name @api/page-state)\n"
   "                                :tagline (:gallery/tagline @api/page-state)}\n"
   "                               :gallery/data)\n"
   "                  (swap! api/page-state assoc :gallery/route :gallery\n"
   "                         :gallery/name \"\" :gallery/tagline \"\"))}\n"
   "    [:label \"Project name\" [:input {:required true :value (or (:gallery/name @api/page-state) \"\")\n"
   "                                          :on-change #(swap! api/page-state assoc :gallery/name (api/event-value %))}]]\n"
   "    [:label \"What it does\" [:textarea {:required true :value (or (:gallery/tagline @api/page-state) \"\")\n"
   "                                             :on-change #(swap! api/page-state assoc :gallery/tagline (api/event-value %))}]]\n"
   "    [:button {:type \"submit\"} \"Submit project\"]]])\n\n"
   "(defn leaderboard [projects weights]\n"
   "  [:section.product-section\n"
   "   [:header.section-heading [:p \"Live ranking\"] [:h1 \"Leaderboard\"]\n"
   "    [:span.weight-note (str \"Judge \" (:judge weights) \" points, public \" (:public weights) \" point\")]]\n"
   "   [:div.leaderboard-list\n"
   "    (for [[index project] (map-indexed vector projects)]\n"
   "      ^{:key (:id project)} [project-card project (inc index)])]])\n\n"
   "(defn page [_context]\n"
   "  (api/ensure-action! :projects/list {} :gallery/data)\n"
   "  (let [route (or (:gallery/route @api/page-state) :gallery)\n"
   "        {:keys [projects weights]} (:gallery/data @api/page-state)]\n"
   "    [:main.product-shell\n"
   "     [navigation route]\n"
   "     (case route\n"
   "       :submit [submit]\n"
   "       :leaderboard [leaderboard projects weights]\n"
   "       [gallery projects])]))\n\n"
   "(api/register-page! :home page)\n"))

(def ^:private gallery-css
  (str
   workspace-css
   ":host { --gallery-ink: #1c211c; --gallery-green: #285a43; --gallery-paper: #f4f1e7; }\n"
   ".product-shell { min-height: 100dvh; background: #fff; color: var(--gallery-ink); }\n"
   ".product-nav { position: sticky; top: 0; z-index: 2; display: flex; align-items: center; justify-content: space-between; gap: 24px; padding: 18px clamp(20px, 5vw, 72px); border-bottom: 1px solid #deddd6; background: rgb(255 255 255 / 94%); }\n"
   ".product-nav div { display: flex; gap: 5px; }\n"
   ".product-nav button { border: 0; border-radius: 8px; background: transparent; padding: 8px 10px; cursor: pointer; }\n"
   ".product-nav button.active { background: #eceae2; }\n"
   ".product-section { width: min(1180px, calc(100% - 40px)); margin: 0 auto; padding: 64px 0 90px; }\n"
   ".section-heading { max-width: 700px; margin-bottom: 34px; }\n"
   ".section-heading p { margin: 0 0 9px; color: #67665f; }\n"
   ".section-heading h1 { margin: 0; font-family: Georgia, serif; font-size: clamp(36px, 5vw, 66px); font-weight: 500; letter-spacing: -.045em; line-height: 1.02; }\n"
   ".weight-note { display: block; margin-top: 14px; color: #67665f; }\n"
   ".voter-type { display: flex; align-items: center; gap: 8px; margin-bottom: 20px; color: #67665f; }\n"
   ".voter-type select { border: 1px solid #cfcdc3; border-radius: 8px; background: #fff; padding: 7px 9px; }\n"
   ".project-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }\n"
   ".project-card { position: relative; display: grid; min-height: 190px; padding: 24px; border: 1px solid #deddd6; border-radius: 14px; background: var(--gallery-paper); }\n"
   ".project-card:nth-child(3n + 2) { background: #edf1e8; }\n"
   ".project-card h2 { margin: 0 0 7px; font-size: 23px; letter-spacing: -.025em; }\n"
   ".project-card p { margin: 0; color: #626159; line-height: 1.45; }\n"
   ".project-card footer { align-self: end; display: flex; align-items: center; gap: 12px; margin-top: 24px; }\n"
   ".project-card footer > span:first-child { font-weight: 700; }\n"
   ".vote-breakdown { color: #77756d; font-size: 12px; }\n"
   ".project-card footer button { margin-left: auto; border: 0; border-radius: 8px; background: var(--gallery-green); padding: 9px 13px; color: #fff; cursor: pointer; }\n"
   ".podium-place { position: absolute; top: 18px; right: 18px; font-family: Georgia, serif; font-size: 24px; color: var(--gallery-green); }\n"
   ".leaderboard-list { display: grid; gap: 10px; }\n"
   ".leaderboard-list .project-card { min-height: 130px; grid-template-columns: 1fr; }\n"
   ".submit-section { max-width: 760px; }\n"
   ".submit-form { display: grid; gap: 17px; }\n"
   ".submit-form label { display: grid; gap: 7px; color: #55544e; }\n"
   ".submit-form input, .submit-form textarea { border: 1px solid #c9c7bd; border-radius: 10px; padding: 12px; color: inherit; }\n"
   ".submit-form textarea { min-height: 120px; resize: vertical; }\n"
   ".submit-form button { justify-self: start; border: 0; border-radius: 9px; background: var(--gallery-green); padding: 11px 16px; color: #fff; cursor: pointer; }\n"
   "@media (max-width: 760px) { .product-nav { align-items: flex-start; flex-direction: column; gap: 10px; } .product-nav div { width: 100%; overflow-x: auto; } .project-grid { grid-template-columns: 1fr; } .product-section { padding-top: 42px; } .project-card footer { flex-wrap: wrap; } }\n"))

(def ^:private gallery-migration
  {:name "create-gallery"
   :sql
   "CREATE TABLE projects (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, tagline TEXT NOT NULL, created_at TEXT NOT NULL);\nCREATE TABLE votes (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL REFERENCES projects(id), voter_type TEXT NOT NULL CHECK (voter_type IN ('public', 'judge')), created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);\nCREATE INDEX votes_project_idx ON votes(project_id);\nINSERT INTO projects (name, tagline, created_at) VALUES ('Patchwork', 'Turn a workshop conversation into a tested release plan.', '2026-07-15 09:01:00'), ('Fieldnote', 'Collect research observations and reveal recurring product signals.', '2026-07-15 09:02:00'), ('Kindred', 'Match volunteer skills with neighborhood requests.', '2026-07-15 09:03:00'), ('Relay', 'Give every handoff a working example and a clear owner.', '2026-07-15 09:04:00'), ('Tidemark', 'Compare climate adaptation ideas against local constraints.', '2026-07-15 09:05:00'), ('Commonroom', 'Run lightweight public reviews without spreadsheet drift.', '2026-07-15 09:06:00');"})

(defn- sidebar-change
  []
  (provider/change-result
   "The conversation is now a compact floating workspace."
   "Make the conversation sidebar float"
   [{:path "src/client/runtime/sidebar.cljs" :content floating-sidebar}
    {:path "styles/runtime.css" :content workspace-css}]
   []))

(defn- dark-theme-change
  []
  (provider/change-result
   "The running workspace now uses a dark theme."
   "Apply a dark workspace theme"
   [{:path "styles/runtime.css" :content dark-workspace-css}]
   []))

(defn- floating-css-only-change
  []
  (provider/change-result
   "The dark sidebar is now a floating panel."
   "Float the current dark sidebar"
   [{:path "styles/runtime.css" :content floating-dark-workspace-css}]
   []))

(defn- invalid-client-render-change
  []
  (provider/change-result
   "This deliberately invalid view should never be activated."
   "Invalid browser render fixture"
   [{:path "src/client/runtime/client.cljs" :content invalid-client-view}]
   []))

(defn- tetris-change
  []
  (provider/change-result
   "Tetris is running with automatic falling and keyboard controls."
   "Create a live Tetris game"
   [{:path "src/client/runtime/client.cljs" :content tetris-client}
    {:path "styles/runtime.css" :content tetris-css}]
   []))

(defn- gallery-change
  []
  (provider/change-result
   "The gallery, submission flow, voting actions, and persistent leaderboard are running."
   "Create the product gallery and voting workflow"
   [{:path "src/shared/runtime/domain.cljc" :content gallery-domain-v1}
    {:path "src/server/runtime/server.clj" :content (gallery-server 1)}
    {:path "test/runtime/domain_test.cljc" :content (gallery-domain-test 1 true)}
    {:path "src/client/runtime/client.cljs" :content (gallery-client false)}
    {:path "styles/runtime.css" :content gallery-css}]
   [gallery-migration]))

(defn- scoring-change
  []
  (provider/change-result
   "Judge votes now count for three points, public votes count for one, and the top three are marked on the leaderboard."
   "Weight judge votes and show the top three"
   [{:path "src/shared/runtime/domain.cljc" :content gallery-domain-v2}
    {:path "src/server/runtime/server.clj" :content (gallery-server 3)}
    {:path "test/runtime/domain_test.cljc" :content (gallery-domain-test 3 false)}
    {:path "src/client/runtime/client.cljs" :content (gallery-client true)}]
   []))

(defrecord FakeProvider [delay-ms calls])

(defn create-provider
  ([]
   (create-provider {}))
  ([{:keys [delay-ms] :or {delay-ms 0}}]
   (->FakeProvider delay-ms (atom 0))))

(defn- contains-any?
  [text needles]
  (some #(str/includes? text %) needles))

(defn- restore-result
  [prompt runtime-version]
  (let [match (re-find #"(?i)(?:version|checkpoint|버전|체크포인트)\s*#?\s*(\d+)" prompt)
        version (if match
                  (parse-long (second match))
                  (max 0 (dec runtime-version)))]
    {:kind :restore
     :assistant-message (str "Restoring checkpoint " version ".")
     :clarification-question nil
     :restore-version version
     :change nil}))

(extend-type FakeProvider
  provider/Provider
  (ready? [this]
    {:ready? true :provider :fake :calls @(:calls this)})

  (generate! [this {:keys [prompt runtime-version session-id thread-id]}]
    (swap! (:calls this) inc)
    (when (pos? (:delay-ms this))
      (Thread/sleep (:delay-ms this)))
    (let [lower (str/lower-case prompt)
          result
          (cond
            (str/includes? lower "[[fake:timeout]]")
            (throw (provider/error :provider/timeout
                                   "The deterministic timeout fixture fired"))

            (str/includes? lower "[[fake:schema-invalid]]")
            {:kind :clarify
             :assistant-message "Invalid fixture"
             :clarification-question nil}

            (str/includes? lower "[[fake:invalid-source]]")
            (provider/change-result
             "This deliberately invalid fixture should be rejected."
             "Invalid fixture"
             [{:path "../../outside.clj" :content "(System/exit 0)"}]
             [])

            (str/includes? lower "[[fake:css-only-floating]]")
            (floating-css-only-change)

            (str/includes? lower "[[fake:client-render-error]]")
            (invalid-client-render-change)

            (contains-any? lower ["secret" "auth.json" "shell" "filesystem" "mcp" "비밀" "쉘"])
            {:kind :reply
             :assistant-message "That capability is outside this product runtime. I can change the product through its bounded UI, action, data, and public HTTP capabilities."
             :clarification-question nil
             :restore-version nil
             :change nil}

            (contains-any? lower ["restore" "rollback" "checkpoint" "복구" "되돌"])
            (restore-result prompt runtime-version)

            (contains-any? lower ["judge=3" "judge 3" "3 points" "3점" "top 3" "top three" "podium" "포디움"])
            (scoring-change)

            (contains-any? lower ["gallery" "leaderboard" "voting" "vote" "갤러리" "리더보드" "투표"])
            (gallery-change)

            (contains-any? lower ["tetris" "테트리스"])
            (tetris-change)

            (contains-any? lower ["dark theme" "dark mode" "다크테마" "다크 테마"])
            (dark-theme-change)

            (contains-any? lower ["sidebar" "side bar" "사이드바" "floating panel" "플로팅"])
            (sidebar-change)

            (contains-any? lower ["hello" "hi " "안녕" "what can you do" "뭐 할 수"])
            {:kind :reply
             :assistant-message "Tell me the outcome you want. I can discuss it, ask one focused question, or change this running product."
             :clarification-question nil
             :restore-version nil
             :change nil}

            :else
            {:kind :clarify
             :assistant-message "I need one product decision before changing the running page."
             :clarification-question "What should a person be able to accomplish when this change is finished?"
             :restore-version nil
             :change nil})
          resulting-thread-id (or thread-id
                                  (some-> session-id str)
                                  "00000000-0000-0000-0000-000000000001")]
      (provider/generation result resulting-thread-id))))
