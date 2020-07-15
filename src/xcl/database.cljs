(ns xcl.database
  (:require [xcl.data-model :as model]
            [malli.json-schema]
            [camel-snake-kebab.core :as csk]
            ["json-schema-sequelizer" :as JSONSchemaSequelizer]
            ;; included by sequelize
            ["inflection" :as inflection]
            ["chalk" :as chalk]))


;; NOTE: usage of js-invoke is to silence compile-time warnings like
;;   Cannot infer target type in expression (. (aget builder "sequelize" "dialect" "QueryGenerator") bulkInsertQuery table-name (clj->js records))


(def $default-settings
  {:dialect "sqlite"
   :storage ":memory:"})

(defn make-join-table-name [left right]
  (str left "__" right))

(def $ref-pkey {:$ref "dataTypes#/definitions/PK"})

(defn model-to-json-schema-sequelizer [model-def table-name]
  {:$schema
   (as-> model-def $
     (malli.json-schema/transform $)
     (dissoc $ :type) ;; this should be a  {:type "object"}  association
     (update-in $ [:properties]
                (fn [props]
                  (->> (dissoc props :id)
                       (map (fn [[key prop-def]]
                              (let [foreign-model-name (->> key
                                                            (name)
                                                            (.singularize inflection))]
                                [key
                                 (case (get-in prop-def [:type])

                                   ;; assume one-to-many join
                                   "object"
                                   {:$ref foreign-model-name
                                    :belongsTo true}

                                   ;; assume many-to-many join
                                   "array" 
                                   {:items
                                    {:$ref foreign-model-name
                                     :belongsToMany {:through (make-join-table-name
                                                               table-name
                                                               foreign-model-name)}}}
                                   
                                   ;; assume primitive
                                   prop-def)])))
                       (into {:id $ref-pkey}))))
     (assoc $ :id table-name)
     (merge $ {
               ;; sequelize options
               :options {
                         ;; prevent pluralization and use :id as table name;
                         ;; note if you use :tableName, the override will work
                         ;; but pluralization will still be applied
                         :freezeTableName true
                         
                         ;; prevent auto-creation of createdAt / updatedAt
                         ;; :timestamps false
                         
                         }}))})

(defn sql-exec-logger [log]
  (when-let [[_ prefix sql]
             (re-find #"([^:]+:)\s(.+)" log)]
    (js/console.log
     (str (.green chalk prefix)
          "\n  "
          (.yellow chalk sql)))))

(defonce $builder (atom nil))

(defn initialize-database! [json-schema-sequelizer-settings
                            & [on-ready]]
  
  (when-not @$builder
    (reset! $builder
            (JSONSchemaSequelizer.
             (clj->js json-schema-sequelizer-settings)
             (clj->js
              {:dataTypes
               {:definitions
                {:PK {:type "integer"
                      :minimum 1
                      :primaryKey true
                      :autoIncrement true}}}})
             (.cwd js/process))))
  
  (doto @$builder
    #_(.add
       ;; see https://github.com/json-schema-faker/json-schema-sequelizer
       (clj->js {:$schema
                 {:options {}
                  :id "Tag"
                  ;; model fields
                  :properties { ;; resolved from an external/local reference (see below)
                               :id $ref-pkey
                               ;; regular fields
                               :name {:type "string"}
                               ;; ID-references are used for associating things
                               :children {:items {:$ref "Tag"}}}
                  :required ["id" "name"]
                  }
                 ;; UI-specific details
                 :$uiSchema {
                             ;; use with react-jsonschema-form (built-in)
                             }
                 ;; RESTful settings
                 :$attributes {
                               ;; ensure all read-operations retrieve Tag"s name
                               ;; for individual actions try setting up `findOne`
                               :findAll ["name"]
                               }
                 ;; any other property will be used as the model definition
                 :hooks {}
                 :getterMethods {}
                 :setterMethods {}
                 :classMethods {}
                 :instanceMethods {}}))

    #_(.add
       ;; see https://github.com/json-schema-faker/json-schema-sequelizer
       (clj->js {:$schema
                 {:properties
                  {:id {:$ref "dataTypes#/definitions/PK"} :blah {:type "string"}}
                  :required [:id :blah]
                  :id "Thing"
                  :options {:tableName "THANG"}}}))
    )

  (doseq [[table-name model-def] model/table-model-mapping]
    (.add @$builder (clj->js
                     (model-to-json-schema-sequelizer
                      model-def table-name))))

  (-> @$builder
      (.connect)
      (.then
       (fn []
         
         ;; show for single model
         #_(-> builder
               (aget "models" "Text")
               (.sync (clj->js {:logging js/console.log})))
         
         (-> @$builder
             (.sync
              (clj->js
               {:logging sql-exec-logger}))
             (.then
              (fn [_]
                (when on-ready
                  (on-ready @$builder)))))))))
  
(defn get-table-actions-manager [builder table-name]
  (-> (.resource JSONSchemaSequelizer
                 builder
                 nil
                 table-name)
      (aget "actions")))

(defn has-table?
  ([table-name]
   (has-table? @$builder table-name))
  ([builder table-name]
   (boolean (aget builder "models" table-name))))

(defn generate-insert-query [builder table-name records]
  (let [now (js/Date.)]
    (-> (aget builder "sequelize" "dialect" "QueryGenerator")
        (js-invoke
         "bulkInsertQuery"
         table-name
         (->> records
              (map (fn [rec]
                     ;; WARNING: this is required ONLY if timestamps is on
                     (assoc rec
                            :createdAt now
                            :updatedAt now)))
              (clj->js))))))

(defn run-sql!
  ([sql] (run-sql! @$builder sql))
  ([builder sql]
   (-> (aget builder "sequelize")
       (js-invoke
        "query"
        sql
        (clj->js {:logging sql-exec-logger})))))

(defn seed-data! [builder data]
  (doseq [[table-name records] data]
    (println
     (str (.blue chalk ">>> [SEEDING]: ")
          table-name))
    (doseq [record records]
      (println (.cyan chalk (str ">>>          - " record)))
      (-> (get-table-actions-manager builder table-name)
          (.create (clj->js record))))))

(defn resolve-sequelize-data-values [query-result]
  (->> (some-> (aget query-result "dataValues")
               (js->clj :keywordize-keys true))
       (map (fn [[key maybe-sequelize-record]]
              [key
               (cond (sequential? maybe-sequelize-record)
                     (->> maybe-sequelize-record
                          (map resolve-sequelize-data-values))

                     (aget maybe-sequelize-record "dataValues")
                     (resolve-sequelize-data-values maybe-sequelize-record)
                     
                     :else maybe-sequelize-record)]))
       (into {})))

(defn find-by-id
  ([table-name ^int id callback]
   (find-by-id @$builder table-name id callback))
  ([builder table-name id callback]
   (-> (aget builder "models" table-name)
       (js-invoke "findByPk" id)
       (.then (fn [result]
                (callback
                 (resolve-sequelize-data-values result)))))))

(defn find-by-attribute-dispatch
  [builder table-name selector extent callback]
  {:pre [(#{"findOne" "findAll"} extent)]}
  (-> (aget builder "models" table-name)
      (js-invoke extent 
                 (clj->js {:where selector}))
      (.then (fn [result]
               (if (aget result "length")
                 (callback (->> (array-seq result)
                                (map resolve-sequelize-data-values)))
                 (callback
                  (resolve-sequelize-data-values result)))))))

(defn find-by-attribute-one
  ([table-name selector callback]
   (find-by-attribute-one @$builder table-name selector callback))
  ([builder table-name selector callback]
   (find-by-attribute-dispatch
    builder table-name selector "findOne" callback)))

(defn get-model-join-tables [model-def]
  (->> model-def
       (rest)
       (filter (fn [[key maybe-validator]]
                 (and (not= key :id)
                      (not (fn? maybe-validator)))))
       (map first)))

;; TODO: change to findAndCountAll
(defn find-by-attribute-all
  ([table-name selector callback]
   (find-by-attribute-all @$builder table-name selector callback))
  ([builder table-name selector callback]
   (let [sequelize-models (aget builder "models")
         model-def (model/table-model-mapping table-name)]
     (-> (aget sequelize-models table-name)
         (js-invoke
          "findAll"
          (clj->js
           (doto
               {:where selector
                ;; :limit 2
                :include (->> (get-model-join-tables model-def)
                              (map (fn [model-key]
                                     ;; NOTE: due to pluralization,
                                     ;; the look-up of model-name results in (probably) a singular name,
                                     ;; but the property name on the target model (if plural) is unchanged
                                     (let [model-name (->> (name model-key)
                                                           (.singularize inflection))]
                                       {:model (aget sequelize-models model-name)
                                        :as (name model-key)}))))})))
         (.then
          (fn [result]
            (callback
             (->> (array-seq result)
                  (map resolve-sequelize-data-values)))))))))

;; special case for "content" table, to join across the properties table
(defn load-content
  ([callback]
   (load-content nil callback))
  ([options callback]
   (load-content @$builder options callback))
  ([builder options callback]
   (let [sequelize-models (aget builder "models")
         model-name "content"
         model-def (model/table-model-mapping model-name)]
     (-> (aget sequelize-models model-name)
         (js-invoke
          "findAll"
          (->> options
               (merge {:include [{:model (aget sequelize-models "property")
                                  :as "properties"
                                  :include (->> (get-model-join-tables
                                                 (model/table-model-mapping "property"))
                                                (map (fn [join-model-key]
                                                       (let [join-model-name (name join-model-key)]
                                                         {:model (aget sequelize-models join-model-name)
                                                          :as join-model-name}))))}]})
               (clj->js)))
         (.then (fn [result]
                  (->> (array-seq result)
                       (map resolve-sequelize-data-values)
                       (callback))))))))
