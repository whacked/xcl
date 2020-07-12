(ns xcl.database
  (:require [xcl.data-model :as model]
            [malli.json-schema]
            [camel-snake-kebab.core :as csk]
            ["json-schema-sequelizer" :as JSONSchemaSequelizer]
            ;; included by sequelize
            ["inflection" :as inflection]
            ["chalk" :as chalk]))

(def $default-settings
  {:dialect "sqlite"
   :storage ":memory:"})

(def $ref-pkey {:$ref "dataTypes#/definitions/PK"})

(defn model-to-json-schema-sequelizer [model-def table-name]
  {:$schema
   (as-> model-def $
     (malli.json-schema/transform $)
     (dissoc $ :type) ;; it should be {:type "object"}
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
                                     :belongsToMany {:through (str table-name
                                                                   "__"
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

(defn initialize-database! [json-schema-sequelizer-settings]
  (let [builder (JSONSchemaSequelizer.
                 (clj->js json-schema-sequelizer-settings)
                 (clj->js
                  {:dataTypes
                   {:definitions
                    {:PK {:type "integer"
                          :minimum 1
                          :primaryKey true
                          :autoIncrement true}}}})
                 (.cwd js/process))]
    (doto builder
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
      (.add builder (clj->js
                     (model-to-json-schema-sequelizer
                      model-def table-name))))

    (-> builder
        (.connect)
        (.then
         (fn []
                 
           ;; show for single model
           #_(-> builder
                 (aget "models" "Text")
                 (.sync (clj->js {:logging js/console.log})))
                 
           (.sync builder
                  (clj->js
                   {:logging
                    (fn [log]
                      (when-let [[_ prefix sql]
                                 (re-find #"([^:]+:)\s(.+)" log)]
                        (js/console.log
                         (str (.green chalk prefix)
                              "\n  "
                              (.yellow chalk sql)))))})))))))
