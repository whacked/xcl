(ns xcl.seed-data-loader
  (:require ["fs" :as fs]
            ["path" :as path]
            [xcl.database :as db]
            [cljs.reader]))

(defonce example-data-input
  (-> (.readFileSync
       fs
       (.join path (.cwd js/process)
              "src" "example-seed-data.edn")
       "utf-8")
      (cljs.reader/read-string)))

(def example-seed-data
  (let [{:keys [symbol-map text-map
                symbol-text-pairs
                content-props]} example-data-input
        
        symbol-seed
        (->> symbol-map
             (map (fn [[s id]] {:id id :symbol (name s)})))
        
        text-seed
        (->> text-map
             (map (fn [[t id]] {:id id :text t})))

        property-map
        (->> symbol-text-pairs
             (map-indexed
              (fn [i [symbol text]]
                [[symbol text] (inc i)]))
             (into {}))
        
        property-seed
        (->> property-map
             (map
              (fn [[[symbol text] id]]
                {:id id
                 :symbolId (symbol-map symbol)
                 :textId   (text-map   text)})))
        
        content-map
        (->> content-props
             (map-indexed
              (fn [i props]
                [(inc i) (->> props
                              (map (fn [[symbol text]]
                                     (property-map
                                      [symbol text]))))]))
             (into {}))

        content-seed
        (->> content-map
             (map (fn [[id _prop-ids]] {:id id})))
        ]
    {"symbol" symbol-seed
     "text" text-seed
     "property" property-seed
     "content" content-seed
     
     (db/make-join-table-name
      "content" "property")
     (->> content-map
          (map (fn [[content-id prop-ids]]
                 (->> prop-ids
                      (map (fn [prop-id]
                             {:contentId content-id
                              :propertyId prop-id})))))
          (apply concat))}))
