(ns xcl.seed-data-loader
  (:require ["fs" :as fs]
            ["path" :as path]
            [taoensso.timbre :refer [info]]
            [xcl.database :as db]
            [cljs.reader]))
            ["chalk" :as chalk]
            [promesa.core :as promesa]

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
  (let [content-table (aget builder "models" "content")
        content--property-orm (aget builder "models" "content__property")]
    
    (->> content-props
         ((fn iterate-remain-content-props [remain-content-props]
            (when-let [prop-coll (first remain-content-props)]
              (let [property-ids (atom [])]
                (->> prop-coll
                     ((fn iterate-remain-props [remain-props]
                        (if (empty? remain-props)
                          
                          ;; create a content record + join with property records
                          (-> (js-invoke content-table "create")
                              (.then (fn [content-record]
                                       (let [content-prop-associations
                                             (->> @property-ids
                                                  (map (fn [property-id]
                                                         {:contentId (aget content-record "id")
                                                          :propertyId property-id})))]
                                         (js-invoke content--property-orm "bulkCreate"
                                                    (clj->js content-prop-associations)))))
                              (.then (fn [_content-record]
                                       (iterate-remain-content-props
                                        (rest remain-content-props)))))
                          
                          (let [[symbol text] (first remain-props)
                                new-symbol (atom nil)
                                new-text (atom nil)]
                            (-> (promesa/do!
                                 (db/find-or-create builder "symbol" {:symbol symbol} (partial reset! new-symbol))
                                 (db/find-or-create builder "text" {:text text} (partial reset! new-text)))
                                (promesa/then
                                 (fn []
                                   (db/find-or-create
                                    builder "property"
                                    {:symbolId (:id @new-symbol)
                                     :textId (:id @new-text)}
                                    ;; unclear why this changes behavior, but it fails with
                                    ;;   UnhandledPromiseRejectionWarning: SequelizeUniqueConstraintError: Validation error
                                    ;; when run without a runnable callback
                                    identity)))
                                (promesa/then
                                 (fn [prop-record]
                                   (swap! property-ids conj (:id prop-record))
                                   (iterate-remain-props (rest remain-props)))))))))))))))))
