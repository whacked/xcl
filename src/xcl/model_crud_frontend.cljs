(ns xcl.model-crud-frontend
  (:require-macros
   [hiccups.core :as h])
  (:require
   [goog.dom :as gdom]
   [reagent.dom :as rdom]
   [reagent.ratom :as r]
   [xcl.data-model :as model]
   [malli.generator :as mg]
   [ajax.core :as ajax]
   [hiccups.runtime]
   ["inflection" :as inflection]
   ["tabulator-tables" :as Tabulator]
   ["react-tabulator" :refer [ReactTabulator]]
   ["react-tabs" :refer [Tab Tabs TabList TabPanel]]
   ["color-hash" :as ColorHash]))



(def colorHash-dark (new ColorHash (clj->js {:lightness 0.3})))
(defn make-dark-color [any]
  (.hex colorHash-dark any))

(def colorHash-light (new ColorHash (clj->js {:lightness 0.9})))
(defn make-light-color [any]
  (.hex colorHash-light any))

(defn render-property [prop-symbol prop-value]
  (case prop-symbol
    :url [:a {:href prop-value} prop-value]
    
    prop-value))

(defn property-collection-to-property-map [prop-coll]
  (->> prop-coll
       (reduce
        (fn [out {:keys [symbol string]}]
          (update out
                  symbol
                  (fn [current]
                    (conj (or current #{})
                          string))))
        {})))

(defn process-property-collection [prop-coll]
  (let [prop-map (property-collection-to-property-map prop-coll)]
    (cond (and ((:kind prop-map #{}) "file")
               (:file-path prop-map))
          [:span
           "open: "
           [:a
            {:href (str "/file/" (first (:file-path prop-map)))}
            (first (:file-path prop-map))]]

          :else
          nil)))

(defn render-content-properties [properties]
  (let [prop-coll (->> properties
                       (map (fn [js-prop]
                              {:symbolId (aget js-prop "symbol" "id")
                               :symbol   (keyword (aget js-prop "symbol" "symbol"))
                               :stringId   (aget js-prop "string" "id")
                               :string     (aget js-prop "string" "string")})))]

    (h/html
     [:table
      {:width "100%"}
      [:tbody
       (->> prop-coll
            (map (fn [prop]
                   [:tr
                    [:td
                     {:width "5%"}
                     [:code (:symbolId prop)]]
                    [:td
                     {:width "15%"}
                     [:code
                      {:style (str "color:" (make-dark-color (:symbol prop)) ";"
                                   "background: " (make-light-color (:symbol prop)) ";")}
                      (:symbol prop)]]
                    [:td
                     {:width "5%"}
                     [:code (:stringId prop)]]
                    [:td
                     (render-property
                      (keyword (:symbol prop))
                      (:string prop))]])))
       [:tr
        [:td
         {:colspan 4}
         (process-property-collection prop-coll)]]]])))

(defn table-component [model-name data-atom]
  [:div
   [:> ReactTabulator
    {:data @data-atom
     :cellEdited (fn [cell]
                   (js/console.info cell)
                   (let [table-name (-> cell
                                        (.getColumn)
                                        (.getDefinition)
                                        (aget "field"))
                         cell-id (-> (.getData cell)
                                     (aget "id"))
                         new-cell-value (.getValue cell)]
                     (ajax/POST
                      (str (aget js/location "href")
                           "/" table-name)
                      {:response-format :json
                       :format (ajax/json-request-format)
                       :params {:id cell-id
                                (keyword table-name) new-cell-value}
                       :handler (fn [result]
                                  (->> result
                                       (js/JSON.parse)
                                       (js/console.info))
                                  
                                  (let [row-index-1 (-> cell
                                                        (.getRow)
                                                        (.getIndex))
                                        collection-index (dec row-index-1)]
                                    (swap! data-atom
                                           (fn [js-table-array]
                                             (aset js-table-array collection-index table-name new-cell-value)
                                             js-table-array))))
                       
                       :error-handler (fn [err]
                                        (js/alert (str "ERROR: edit for the cell failed"))
                                        (js/console.error (pr-str err)))})))
     :columns (loop [remain-attrs (rest (model/table-model-mapping model-name))
                     out []]
                
                (if (empty? remain-attrs)
                  out
                  
                  (let [[key validator] (first remain-attrs)
                        
                        field-name (name key)
                        
                        column
                        (merge
                         {:title field-name
                          :field field-name}
                         (if (= :id key)
                           nil
                           {:editor "input"}))]

                    (recur (rest remain-attrs)

                           (if (fn? validator)

                             ;; primitive
                             (conj out
                                   (merge
                                    {:title field-name
                                     :field field-name}
                                    (if (= :id key)
                                      {:width 80}
                                      {:editor "input"})))
                             
                             ;; joined model
                             (conj
                              out
                              (let [singularized-field-name
                                    (.singularize inflection field-name)]
                                (if (= field-name
                                       singularized-field-name)
                                  (let [foreign-key (str field-name "Id")]
                                    {:title foreign-key
                                     :field foreign-key})

                                  {:title field-name
                                   :field field-name
                                   :formatter (fn [cell _formatter-params _on-rendered]
                                                (let [value (.getValue cell)]
                                                  (if (< 0 (aget value "length"))
                                                    (render-content-properties value)
                                                    value)))}))))))))}]])

(defn setup-ui! []
  (rdom/render
   [(fn []
      [:div
       [:> Tabs
        [:> TabList
         (->> model/table-model-mapping
              (map first)
              (sort)
              (map (fn [table-name]
                     ^{:key ["tab" table-name]}
                     [:> Tab table-name])))]

        (->> model/table-model-mapping
             (map first)
             (sort)
             (map (fn [table-name]
                    (let [model-def (model/table-model-mapping table-name)
                          data-atom (r/atom
                                     (take 10 (repeatedly (fn [] (mg/generate model-def)))))]
                      
                      (ajax/GET
                       (str (aget js/location "href")
                            "/" table-name)
                       {:response-format :json
                        :handler (fn [results]
                                   (->> results
                                        (js/JSON.parse)
                                        (reset! data-atom)))
                        
                        :error-handler (fn [err]
                                         (js/console.error "=== ERROR ===")
                                         (js/console.warn err))})
                      
                      ^{:key ["panel" table-name]}
                      [:> TabPanel
                       [(fn []
                          (table-component table-name data-atom))]]))))]])]
   (gdom/getElement "main")))

(defn -crud-main []
  (setup-ui!))

(-crud-main)
