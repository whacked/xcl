(ns xcl.model-crud-frontend
  (:require
   [goog.dom :as gdom]
   [reagent.dom :as r]
   [xcl.data-model :as model]
   [malli.generator :as mg]
   ["tabulator-tables" :as Tabulator]
   ["react-tabulator" :refer [ReactTabulator]]
   ["react-tabs" :refer [Tab Tabs TabList TabPanel]]))

(defn setup-ui! []
  (r/render
   [(fn []
      [:div
       [:> Tabs
        [:> TabList
         (->> model/table-model-mapping
              (map (fn [[table-name _]]
                     [:> Tab table-name])))]

        (->> model/table-model-mapping
             (map (fn [[table-name model-def]]
                    (let [data (take 20 (repeatedly (fn [] (mg/generate model-def))))
                          columns (->> (rest model-def)
                                       (map (fn [[key validator]]
                                              (let [field-name
                                                    (if (fn? validator)
                                                      (name key)
                                                      ;; foreign key
                                                      (str (name key) "Id"))]
                                                (merge
                                                 {:title field-name
                                                  :field field-name}
                                                 (if (= :id key)
                                                   nil
                                                   {:editor "input"}))))))]
                      [:> TabPanel
                       [:> ReactTabulator
                        {:data data
                         :columns columns}]]))))]
       ]
      )]
   (gdom/getElement "main")))

(defn -crud-main []
  (setup-ui!))

(-crud-main)
