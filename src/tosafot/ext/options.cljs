(ns tosafot.ext.options
  "Options page: provider list and general settings."
  (:require [clojure.string :as str]
            [tosafot.config :as config]
            [tosafot.core :as core]
            [tosafot.dom :refer [h]]
            [tosafot.files :as files]))

(defonce settings (atom nil))
(defonce status (atom nil))
(defonce graph (atom nil))   ; {:handle :perm :error}

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

;;; :place stays "toolbar" / "menu" / "off" in storage: On = in the menus, Button = also on the toolbar.

(defn- set-place! [i place] (update-action! i :place place) (render!))

(defn- action-row [i {:keys [label prompt instructions place] :as a}]
  (let [on? (not= place "off")]
    [:div {:class (str "action-row" (when-not on? " off"))}
     [:div {:class "row"}
      [:input {:type "text" :class "label" :value label :placeholder "Name" :title "Name"
               :on-input #(update-action! i :label (.. % -target -value))}]
      [:input {:type "text" :class "prompt" :value prompt :placeholder "Question (shown in the thread)"
               :title "Question (shown in the thread)"
               :on-input #(update-action! i :prompt (.. % -target -value))}]
      [:label {:class "inline place on" :title "Show it in the prompt menus"}
       [:input {:type "checkbox" :checked on?
                :on-change #(set-place! i (if (.. % -target -checked) "menu" "off"))}]
       "On"]
      [:label {:class "inline place button" :title "Also show it as a button on the selection toolbar"}
       [:input {:type "checkbox" :checked (= place "toolbar")
                :on-change #(set-place! i (if (.. % -target -checked) "toolbar" "menu"))}]
       "Button"]
      [:div {:class "row-buttons"}
       [:button {:title "Move up" :on-click #(move-action! i -1)} "↑"]
       [:button {:title "Move down" :on-click #(move-action! i 1)} "↓"]
       (when-not (core/builtin? a)
         [:button {:class "remove"
                   :on-click #(do (swap! settings update :actions
                                         (fn [as] (into (subvec as 0 i) (subvec as (inc i)))))
                                  (render!))} "Remove"])]]
     [:textarea {:class "instructions" :value instructions
                 :rows (max 1 (js/Math.ceil (/ (count instructions) 100)))
                 :placeholder "Extra instructions to the model (optional, not shown)"
                 :on-input #(update-action! i :instructions (.. % -target -value))}]]))

(defn- actions-editor [actions]
  [:fieldset {:class "actions-editor"}
   [:legend "Prompts"]
   [:p {:class "help"} "Each prompt has a name, the question it asks (shown in the thread), and optional "
    "extra instructions sent to the model. Prompts that are On appear in the selection toolbar's ⋯ menu "
    "and in a note's Prompts menu. Choosing one puts its question in the box for you to edit before asking. "
    "Button also puts it on the toolbar. To add one, type it in a note and "
    "choose Prompts ▾ → Save prompt…"]
   (map-indexed action-row actions)
   [:div {:class "actions"}
    [:button {:on-click #(do (swap! settings update :actions conj
                                    {:id (str (random-uuid)) :label "New prompt" :place "menu"
                                     :prompt "" :instructions ""})
                             (render!))}
     "Add prompt"]
    [:button {:on-click #(do (swap! settings assoc :actions core/builtin-actions) (render!))}
     "Reset all to defaults"]]])

;;; Export to notebook. The folder is saved as soon as it's chosen; :pkg-folder (its name) tells
;;; content scripts that export is set up, since they can't see the handle.

(defn- save-key! [k v]
  (swap! settings assoc k v)
  (.then (config/load-settings) #(config/save! (assoc % k v))))

(defn- load-graph! []
  (-> (files/graph-handle)
      (.then (fn [h] (if h (.then (files/permission h) #(reset! graph {:handle h :perm %})) (reset! graph nil))))
      (.then #(render!))))

(defn- choose-graph! []
  (-> (js/showDirectoryPicker #js {:mode "readwrite" :id "tosafot-graph"})
      (.then (fn [^js h]
               (-> (files/set-graph-handle! h)
                   (.then #(save-key! :pkg-folder (.-name h)))
                   (.then load-graph!))))
      (.catch (fn [^js e]
                (when-not (= "AbortError" (.-name e))
                  (swap! graph assoc :error (.-message e))
                  (render!))))))

(defn- picker-help
  "Why the folder picker isn't available, and how to turn it on, or nil if it is."
  []
  (when-not (exists? js/showDirectoryPicker)
    (if (exists? js/navigator.brave)
      [:p {:class "status error picker-help"}
       "Brave turns off the folder picker this needs. To turn it on, open "
       [:code "brave://flags/#file-system-access-api"] ", set “File System Access API” to Enabled, "
       "relaunch Brave, then come back here."]
      [:p {:class "status error picker-help"}
       "This browser doesn't support the folder picker (the File System Access API) that this needs."])))

(defn- forget-graph! []
  (-> (files/set-graph-handle! nil)
      (.then #(save-key! :pkg-folder nil))
      (.then load-graph!)))

(defn- pkg-editor [{:keys [pkg-tag pkg-label]}]
  (let [{:keys [^js handle perm error]} @graph
        help (picker-help)]
    [:fieldset {:class "pkg"}
     [:legend "Export to notebook"]
     [:p {:class "help"} "Add notes to an Obsidian, Logseq, or other Markdown-based notebook. Choose its "
      "daily notes folder (for a Logseq graph, the graph folder: its journals folder is used). An export "
      "goes at the end of the latest note there, by file name, as an outline block headed by the page's "
      "link and the tag below. Exporting again updates that block. The menu item appears once a folder "
      "and tag are set."]
     help
     [:div {:class "row graph"}
      [:span {:class "folder"} (if handle (str "Folder: " (.-name handle)) "No folder chosen.")]
      [:div {:class "row-buttons"}
       (when-not help
         [:button {:class "choose" :on-click choose-graph!} (if handle "Change…" "Choose folder…")])
       (when (and handle (not= "granted" perm))
         [:button {:class "grant"
                   :on-click #(-> (files/request-permission handle) (.then load-graph!))}
          "Allow access"])
       (when handle [:button {:class "forget" :on-click forget-graph!} "Forget"])]]
     (when (and handle (not= "granted" perm))
       [:p {:class "status error"} "Chrome needs your permission again to write to this folder."])
     (when error [:p {:class "status error"} (str "⚠ " error)])
     [:div {:class "row"}
      (field "Tag"
             [:input {:type "text" :class "pkg-tag" :value pkg-tag :placeholder "tosafot"
                      :on-input #(swap! settings assoc :pkg-tag (.. % -target -value))}])
      (field "Menu item name"
             [:input {:type "text" :class "pkg-label" :value pkg-label :placeholder "Export to notebook"
                      :on-input #(swap! settings assoc :pkg-label (.. % -target -value))}])]]))

(defn- invalid-actions [actions]
  (filter #(or (str/blank? (:label %)) (str/blank? (:prompt %))) actions))

(defn- view [{:keys [providers system-prompt show-bubble? open-notes? actions] :as s}]
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
       "Show the toolbar when text is selected (Alt+Shift+A opens a note either way)"]
      [:label {:class "inline"}
       [:input {:type "checkbox" :checked open-notes?
                :on-change #(swap! settings assoc :open-notes? (.. % -target -checked))}]
       "Open a page's notes when it loads (notes you close stay closed)"]
      (field "Extra system prompt"
             [:textarea {:value system-prompt
                         :on-input #(swap! settings assoc :system-prompt (.. % -target -value))}])]
     (actions-editor actions)
     (pkg-editor s)
     [:div {:class "actions"}
      [:button {:id "save"
                :on-click (fn []
                            (if-let [bad (seq (invalid-actions (:actions @settings)))]
                              (do (reset! status (str "⚠ Every prompt needs a name and a question ("
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
      (.then #(do (reset! settings %) (render!)))
      (.then load-graph!)))
