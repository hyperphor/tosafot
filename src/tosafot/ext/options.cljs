(ns tosafot.ext.options
  "Options page: provider list and general settings."
  (:require [clojure.string :as str]
            [tosafot.config :as config]
            [tosafot.core :as core]
            [tosafot.dom :refer [h]]))

(defonce settings (atom nil))
(defonce status (atom nil))

(declare render!)

(defn- update-provider! [id k v]
  (swap! settings update :providers
         (fn [ps] (mapv #(if (= id (:id %)) (assoc % k v) %) ps))))

(defn- field [label input] [:div [:label label] input])

(defn- text-input [p k & {:keys [type placeholder]}]
  [:input {:type (or type "text") :value (get p k "") :placeholder placeholder
           :on-input #(update-provider! (:id p) k (.. % -target -value))}])

(defn- provider-card [{:keys [id api] :as p} active?]
  [:fieldset
   [:legend
    [:label {:class "inline"}
     [:input {:type "radio" :name "active" :checked active?
              :on-change #(do (swap! settings assoc :active id) (render!))}]
     (str (:name p) (when active? " (active)"))]]
   (field "Name" (text-input p :name))
   [:div {:class "row"}
    (field "API style"
           [:select {:on-change #(let [api (.. % -target -value)
                                       {:keys [base-url model]} (config/api-styles api)]
                                   (update-provider! id :api api)
                                   (update-provider! id :base-url base-url)
                                   (update-provider! id :model model)
                                   (render!))}
            (for [[k {:keys [label]}] config/api-styles]
              [:option {:value k :selected (= k api)} label])])
    (field "Model" (text-input p :model))]
   (field "Base URL" (text-input p :base-url))
   (field "API key" (text-input p :api-key :type "password"
                                :placeholder (when (= api "openai") "optional for local servers")))
   (when (= api "anthropic")
     (field "Effort (blank = model default)"
            [:select {:on-change #(update-provider! id :effort (.. % -target -value))}
             (for [e ["" "low" "medium" "high" "xhigh" "max"]]
               [:option {:value e :selected (= e (:effort p ""))} (if (= e "") "default" e)])]))
   [:div {:class "actions"}
    [:button {:on-click (fn []
                          (swap! settings update :providers
                                 (fn [ps] (vec (remove #(= id (:id %)) ps))))
                          (render!))}
     "Remove"]]])

;;; Actions editor. Text edits update the atom without re-rendering, to keep focus.

(defn- update-action! [i k v] (swap! settings assoc-in [:actions i k] v))

(defn- move-action! [i d]
  (swap! settings update :actions
         (fn [as] (let [j (+ i d)]
                    (if (< -1 j (count as)) (assoc as i (as j) j (as i)) as))))
  (render!))

(def ^:private places [["toolbar" "Toolbar"] ["menu" "⋯ menu"] ["off" "Off"]])

(defn- action-row [i {:keys [id label prompt instructions place]}]
  (let [builtin (some #(when (= id (:id %)) %) core/builtin-actions)]
    [:div {:class (str "action-row" (when (= place "off") " off"))}
     [:div {:class "row"}
      [:input {:type "text" :class "label" :value label :placeholder "Name" :title "Name"
               :on-input #(update-action! i :label (.. % -target -value))}]
      [:input {:type "text" :class "prompt" :value prompt :placeholder "Question (shown in the thread)"
               :title "Question (shown in the thread)"
               :on-input #(update-action! i :prompt (.. % -target -value))}]
      [:select {:class "place" :title "Where it's shown"
                :on-change #(do (update-action! i :place (.. % -target -value)) (render!))}
       (for [[v l] places] [:option {:value v :selected (= v place)} l])]
      [:div {:class "row-buttons"}
       [:button {:title "Move up" :on-click #(move-action! i -1)} "↑"]
       [:button {:title "Move down" :on-click #(move-action! i 1)} "↓"]
       (if builtin
         [:button {:title "Restore the built-in version"
                   :on-click #(do (swap! settings assoc-in [:actions i] builtin) (render!))} "Reset"]
         [:button {:on-click #(do (swap! settings update :actions
                                         (fn [as] (into (subvec as 0 i) (subvec as (inc i)))))
                                  (render!))} "Remove"])]]
     [:textarea {:class "instructions" :value instructions
                 :rows (max 1 (js/Math.ceil (/ (count instructions) 100)))
                 :placeholder "Extra instructions to the model (optional, not shown)"
                 :on-input #(update-action! i :instructions (.. % -target -value))}]]))

(defn- actions-editor [actions]
  [:fieldset {:class "actions-editor"}
   [:legend "Actions"]
   [:p {:class "help"} "Each action has a name, the question it asks (shown in the thread), and optional "
    "extra instructions sent to the model. Show it on the toolbar that appears when you select text, "
    "under its ⋯ menu, or not at all; shown actions also appear in the Ask box."]
   (map-indexed action-row actions)
   [:div {:class "actions"}
    [:button {:on-click #(do (swap! settings update :actions conj
                                    {:id (str (random-uuid)) :label "New action" :place "menu"
                                     :prompt "" :instructions ""})
                             (render!))}
     "Add action"]
    [:button {:on-click #(do (swap! settings assoc :actions core/builtin-actions) (render!))}
     "Reset all to defaults"]]])

(defn- invalid-actions [actions]
  (filter #(or (str/blank? (:label %)) (str/blank? (:prompt %))) actions))

(defn- view [{:keys [providers system-prompt show-bubble? actions] :as s}]
  (let [active (:id (config/active-provider s))]
    [:div
     (for [p providers] (provider-card p (= active (:id p))))
     [:div {:class "actions"}
      (for [[k {:keys [label]}] config/api-styles]
        [:button {:on-click #(do (swap! settings update :providers conj (config/new-provider k))
                                 (render!))}
         (str "Add " label " provider")])]
     [:fieldset
      [:legend "General"]
      [:label {:class "inline"}
       [:input {:type "checkbox" :checked show-bubble?
                :on-change #(swap! settings assoc :show-bubble? (.. % -target -checked))}]
       "Show the toolbar when text is selected (Alt+Shift+A opens the Ask box either way)"]
      (field "Extra system prompt"
             [:textarea {:value system-prompt
                         :on-input #(swap! settings assoc :system-prompt (.. % -target -value))}])]
     (actions-editor actions)
     [:div {:class "actions"}
      [:button {:id "save"
                :on-click (fn []
                            (if-let [bad (seq (invalid-actions (:actions @settings)))]
                              (do (reset! status (str "⚠ Every action needs a name and a question ("
                                                      (str/join ", " (map #(or (not-empty (:label %)) "unnamed") bad))
                                                      ")."))
                                  (render!))
                              (-> (config/save! @settings)
                                  (.then #(do (reset! status "Saved.") (render!))))))}
       "Save"]
      [:span {:class (str "status" (when (some-> @status (str/starts-with? "⚠")) " error"))} @status]]]))

(defn- render! []
  (let [app (js/document.getElementById "app")]
    (.replaceChildren app (h (view @settings)))))

(defn init []
  (-> (config/load-settings)
      (.then #(do (reset! settings %) (render!)))))
