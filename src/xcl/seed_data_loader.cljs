(ns xcl.seed-data-loader
  (:require ["fs" :as fs]
            ["path" :as path]
            [taoensso.timbre :refer [info]]
            [xcl.database :as db]
            ["chalk" :as chalk]
            [promesa.core :as promesa]
            [cljs.reader]
            [cljs.core.async
             :refer [chan put! take!
                     pub sub
                     >! <!
                     buffer dropping-buffer sliding-buffer
                     timeout close! alts!]
             :refer-macros [go go-loop alt!]]))

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




(defn sim-js-promise
  ([callback ] (sim-js-promise callback 300))
  ([callback delay]
   
   (comment
     ;; usage
     (js/console.log "setting promise...")
     (-> (sim-js-promise
          (fn []
            (js/console.log "THE PROMISE RESULT")))
         (.then (fn []
                  (js/console.log "the then resolver")))))
   
   (new js/Promise
        (fn [resolve]
          (js/setTimeout resolve delay)))))

(def $record-creator-chan (chan))

(def $record-creator-publication
  (pub $record-creator-chan (fn [data] (:method data))))

(defn create-subscriber-channel [signal-name]
  (let [subscriber-channel (chan)]
    (sub $record-creator-publication signal-name subscriber-channel)
    subscriber-channel))

(defn fire-created-event! [created-model-name created-record shared-state]
  (go
    (>! $record-creator-chan
        {:method (str "created-" created-model-name)
         :params [created-record shared-state]})))

(def $SIMULATE? false)

(defn create-symbol! [builder symbol carrier-data]
  (if $SIMULATE?
    (js/setTimeout
     (fn [_] (fire-created-event! "symbol" {:id (rand-int 9999)} carrier-data))
     0)

    (db/find-or-create-notxn
     builder "symbol" {:symbol symbol}
     (fn [record] (fire-created-event! "symbol" record carrier-data)))))

(defn create-text! [builder text carrier-data]
  (if $SIMULATE?
    (js/setTimeout
     (fn [_] (fire-created-event! "text" {:id (rand-int 9999)} carrier-data))
     0)

    (db/find-or-create-notxn
     builder "text" {:text text}
     (fn [record] (fire-created-event! "text" record carrier-data)))))

(defn create-property-if-possible! [builder shared-state]
  (when-let [property-inputs (some->> shared-state
                                      (:property-state)
                                      (deref))]
    (when (some->> property-inputs
                   (vals)
                   (filter nil?)
                   (empty?))
      (println "PROCEEDING WITH " property-inputs)

      (if $SIMULATE?
        (js/setTimeout
         (fn []
           (let [new-property {:property-id (rand-int 9999)}
                 content-state-atom (:content-state shared-state)]
             (fire-created-event! "property" new-property shared-state)))
         0)

        (let [{:keys [symbol-id text-id]} property-inputs
              content-state-atom (:content-state shared-state)]
          (db/find-or-create-notxn
           builder "property" {:symbolId symbol-id
                               :textId text-id}
           (fn [property-record]
             (println property-record)
             (fire-created-event! "property" property-record shared-state))))))))


(defn create-content! [builder properties shared-state]
  (if $SIMULATE?
    (js/setTimeout
     (fn []
       (let [new-content {:content-id (rand-int 9999)}]
         (fire-created-event! "content" new-content shared-state)))
     0)

    (let [content-table (aget builder "models" "content")
          content--property-table (aget builder "models" "content__property")
          add-content-prop-join! (fn [content-id property-id]
                                   (-> content--property-table
                                       (.create (clj->js {:contentId content-id
                                                          :propertyId property-id}))))]
      (-> content-table
          (js-invoke "count")
          (.then
           (fn [nrecords]
             (let [new-content-id (inc nrecords)]
               (-> content-table
                   (.create (clj->js {:id new-content-id}))
                   (.then (fn [content-record]
                            (doseq [prop properties]
                              (add-content-prop-join! new-content-id (:id prop)))))
                   (.then (fn [] (fire-created-event! "content" {:id new-content-id} shared-state)))))))))))

(defn create-event-handler-loop! [model-name event-handler]
  (let [model-creator-event-channel
        (create-subscriber-channel (str "created-" model-name))]
    (go-loop []
      (let [shared-state (:params (<! model-creator-event-channel))]
        (let [colorizer (case model-name
                          "content" (partial js-invoke chalk "cyan")
                          "property" (partial js-invoke chalk "yellow")
                          "symbol" (partial js-invoke chalk "magenta")
                          "text" (partial js-invoke chalk "blue"))]
          (println (str
                    (colorizer
                     (str "[" (clojure.string/upper-case model-name) "] event "))
                    shared-state)))
        (apply event-handler shared-state))
      (recur))))







(defn __bootstrap-from-content-props! [builder content-props]

  (create-event-handler-loop!
   "symbol"
   (fn [symbol-record shared-state]
     (swap! (:property-state shared-state)
            merge {:symbol-id (:id symbol-record)})
     (create-property-if-possible! builder shared-state)))

  (create-event-handler-loop!
   "text"
   (fn [text-record shared-state]
     (swap! (:property-state shared-state)
            merge {:text-id (:id text-record)})
     (create-property-if-possible! builder shared-state)))

  (create-event-handler-loop!
   "property"
   (fn [property-record shared-state]
     (swap! (:content-state shared-state) update :properties conj property-record)
     (let [content-state (-> (:content-state shared-state) (deref))]
       (when (= (:n-waiting content-state)
                (count (:properties content-state)))
         (println "proceeding content with: " (:properties content-state))
         #_(create-content! builder (:properties content-state) shared-state)))))

  (create-event-handler-loop!
   "content"
   (fn [content-record shared-state]
     (println "CONTENT DONE:" content-record)
     #_(let [content-shared-state (-> shared-state (:content-state) (deref))]
       (doseq [prop-rec (-> content-shared-state
                            (:properties))]
         (println "==>"
                  {:contentId (:content-id content-shared-state)
                   :propertyId (:property-id prop-rec)})))))
  
  #_(-> (aget builder "sequelize")
        (js-invoke
         "transaction"
         (fn [txn]
           (db/find-or-create
            builder
            "text"
            {:text "blah"}
            (fn []
              (js/console.log "FINISHED RANS"))
            txn))))

  (defn txn-query-iterator [txn txn-fn args]
    (when (seq args)
      (txn-fn
       txn (first args)
       (fn on-complete [] (txn-query-iterator txn txn-fn (rest args))))))

  
  (doseq [symbol-text-pairs content-props]
    (println "     \\__,> " symbol-text-pairs)
    (let [content-state (atom {:n-waiting (count symbol-text-pairs)
                               :properties []})]
      (doseq [[symbol text] symbol-text-pairs]
        (let [property-state (atom {:symbol-id nil
                                    :text-id nil})
              carrier-data {:property-state property-state
                            :content-state content-state}]
            
          (create-symbol! builder symbol carrier-data)
          (create-text! builder text carrier-data)))))

  #_(db/run-in-transaction!
     builder
     (fn [txn]
       (js/console.log "start transaction")

       #_(doseq [symbol-text-pairs (take 1 content-props)]
           (let [content-state (atom {:n-waiting (count symbol-text-pairs)
                                      :properties []})]
             (doseq [[symbol text] (take 1 symbol-text-pairs)]
               (let [property-state (atom {:symbol-id nil
                                           :text-id nil})
                     carrier-data {:property-state property-state
                                   :content-state content-state}]))))

       (println "=================")
       (doseq [symbol-text-pairs content-props]
         (println "---------------->")
         (println symbol-text-pairs))

     

       #_(->> content-props
              (txn-query-iterator
               txn
               (fn [txn symbol-text-pairs on-complete]
                 #_(db/find-or-create builder "text" {:text text} on-complete txn)

                 ;;[txn text on-complete]
                 (let [content-state (atom {:n-waiting (count symbol-text-pairs)
                                            :properties []})]
          
                   (let [property-state (atom {:symbol-id nil
                                               :text-id nil})
                         carrier-data {:property-state property-state
                                       :content-state content-state}]
           
                     (js/console.log "dumb shit" (str symbol-text-pairs))
                     (->> symbol-text-pairs
                          (txn-query-iterator
                           txn
                           (fn [txn [symbol text] on-complete]
                             (js/console.log " ============ create text!!!" (str symbol) " -- " (str text))
                             ;; (create-symbol! builder symbol carrier-data)
                             ;; (create-text! builder text carrier-data txn)
                             (db/find-or-create
                              builder "text"
                              {:text text}
                              (fn [record]
                                (fire-created-event! "symbol" (assoc carrier-data :symbol-id (rand-int 9999)))
                                (on-complete))
                              txn
                              )
                             )))))
                 (on-complete))))




       (defn run-all-promises [promises]
         (-> (.all js/Promise (clj->js promises))
             (.then (fn []
                      (js/console.log "done with your stupid " (count promises) " promises.")))))

       #_(->> content-props
              (mapv (fn [symbol-text-pairs]
                      (->> symbol-text-pairs
                           (mapv (fn [[symbol text]]
                                   (js/console.log
                                    (str "SYMBOL: " symbol " // text: " text))
                                   (-> (aget builder "models" "text")
                                       (js-invoke
                                        "findOrCreate"
                                        (clj->js {:where {:text text}
                                                  :transaction txn}))
                                       (js-invoke
                                        "spread"
                                        (fn []
                                          (js/console.log "FINISHED FIRST FIND OR CREATE"))))
                                   )))))
              (apply concat)
              (run-all-promises))

       #_(let [symbol-text-pairs (take 1 content-props)
               content-state (atom {:n-waiting (count symbol-text-pairs)
                                    :properties []})
               ]
       
           (let [property-state (atom {:symbol-id nil
                                       :text-id nil})
                 carrier-data {:property-state property-state
                               :content-state content-state}]
             #_(-> js/Promise
                   (.all
                    (->> symbol-text-pairs
                         (map
                          (fn [[symbol text]]
                            (create-text! builder text carrier-data txn)))
                         (clj->js)))
                   (.then
                    (fn []
                      (js/console.log "javascript dumb shit")))))


           #_(txn-query-iterator
              txn
              (fn [txn text on-complete]
                #_(db/find-or-create builder "text" {:text text} on-complete txn)

                ;;[txn text on-complete]
                (let [property-state (atom {:symbol-id nil
                                            :text-id nil})
                      carrier-data {:property-state property-state
                                    :content-state content-state}]
          
                  ;; (create-symbol! builder symbol carrier-data)
                  ;; (create-text! builder text carrier-data txn)
                  (db/find-or-create
                   builder "text"
                   {:text text}
                   (fn [record]
                     (fire-created-event! "symbol" (assoc carrier-data :symbol-id (rand-int 9999)))
                     (on-complete))
                   txn)

                  ;; (js/console.log " ============ create text!!!" (str symbol) " -- " (str text))

          
                  )
        
                )
              ["blah1" "blah2" "blah3"]))
     
       #_(doseq [symbol-text-pairs (take 1 content-props)]
           ;; (println "---> content props // " (count remain-cp))
           ;; (println remain-cp)
           (println "     \\__,> " symbol-text-pairs)
           (let [content-state (atom {:n-waiting (count symbol-text-pairs)
                                      :properties []})]
             (doseq [[symbol text] (take 1 symbol-text-pairs)]
               (let [property-state (atom {:symbol-id nil
                                           :text-id nil})
                     carrier-data {:property-state property-state
                                   :content-state content-state}]
             
                 ;; (create-symbol! builder symbol carrier-data)

                 (js/console.log " ============ create text!!!" (str symbol) " -- " (str text))

                 ;; (create-text! builder text carrier-data txn)
             
             
                 )))

           )

       ))

  
  
  )



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
