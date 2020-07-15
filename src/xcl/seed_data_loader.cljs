(ns xcl.seed-data-loader
  (:require ["fs" :as fs]
            ["path" :as path]
            [taoensso.timbre :refer [info]]
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

(defn bootstrap-from-content-props! [builder content-props]
  (info (str "ingesting: " (count content-props) " props..."))
  (let [ensure-content-prop-link!
        (fn [content-id prop-id nextfn]
          (-> (aget builder "models" "content__property")
              (.create (clj->js {:contentId content-id
                                 :propertyId prop-id}))
              (.then nextfn)))
        
        ensure-content-props!
        (fn [property-ids nextfn]
          (let [content-table (aget builder "models" "content")]
            (-> content-table
                (js-invoke "count")
                (.then
                 (fn [nrecords]
                   (let [new-content-id (inc nrecords)]
                     (-> content-table
                         (.create (clj->js {:id new-content-id}))
                         (.then (fn [content-record]
                                  (let [iterator
                                        (fn iterator [remain-prop-ids]
                                          (if (empty? remain-prop-ids)
                                            (nextfn content-record)
                                            (ensure-content-prop-link!
                                             new-content-id
                                             (first remain-prop-ids)
                                             (fn []
                                               (iterator (rest remain-prop-ids))))))]
                                    (iterator property-ids)))))))))))
        
        iterate-remain-content-props
        (fn iterate-remain-content-props [remain-content-props]
          (when (seq remain-content-props)
            (let [property-ids (atom [])
                  

                  iterate-remain-props
                  (fn iterate-remain-props [remain-props]
                    (if-let [[symbol text] (first remain-props)]
                      (let [ensure-symbol! (fn [symbol nextfn]
                                             (db/find-or-create
                                              builder "symbol"
                                              {:symbol symbol} nextfn))
                          
                            ensure-text! (fn [text nextfn]
                                           (db/find-or-create
                                            builder "text"
                                            {:text text} nextfn))

                            ensure-property! (fn [symbol-id text-id nextfn]
                                               (db/find-or-create
                                                builder "property"
                                                {:symbolId symbol-id
                                                 :textId text-id}
                                                nextfn))]
                        
                        (ensure-symbol!
                         symbol
                         (fn [symbol-record]
                           (ensure-text!
                            text
                            (fn [text-record]
                              (ensure-property!
                               (:id symbol-record)
                               (:id text-record)
                               (fn [prop-record]
                                 (swap! property-ids conj (:id prop-record))
                                 (iterate-remain-props (rest remain-props)))))))))

                      (ensure-content-props!
                       @property-ids
                       (fn [record]
                         (iterate-remain-content-props
                          (rest remain-content-props))))))]

              (iterate-remain-props (first remain-content-props)))))]
    
    (iterate-remain-content-props content-props)))
