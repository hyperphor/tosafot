(ns tosafot.ext.options
  "Options page: provider list and general settings."
  (:require [tosafot.config :as config]
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

(defn- view [{:keys [providers system-prompt show-bubble?] :as s}]
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
       "Show the Ask button when text is selected (Alt+Shift+A works either way)"]
      (field "Extra system prompt"
             [:textarea {:value system-prompt
                         :on-input #(swap! settings assoc :system-prompt (.. % -target -value))}])]
     [:div {:class "actions"}
      [:button {:id "save"
                :on-click (fn []
                            (-> (config/save! @settings)
                                (.then #(do (reset! status "Saved.") (render!)))))}
       "Save"]
      [:span {:class "status"} @status]]]))

(defn- render! []
  (let [app (js/document.getElementById "app")]
    (.replaceChildren app (h (view @settings)))))

(defn init []
  (-> (config/load-settings)
      (.then #(do (reset! settings %) (render!)))))
