(ns xcl.data-model
  (:require
   [schema.core :as s]
   [malli.core :as m]
   [malli.json-schema]))

(def PDbRecord
  {:id s/Int})

(def PSymbol
  (assoc PDbRecord
         :symbol s/Str))

(def PText
  (assoc PDbRecord
         :text s/Str))

(def PProperty
  (assoc PDbRecord
         :symbol PSymbol
         :text PText))

(def PContent
  (assoc PDbRecord
         :properties [PProperty]))


(def MSymbol
  [:map
   [:id int?]
   [:symbol string?]])

#_(m/validate
   MSymbol
   {:id 1
    :symbol "asdf"})

(def MText
  [:map
   [:id int?]
   [:text string?]])

(def MProperty
  [:map
   [:id int?]
   [:symbol MSymbol]
   [:text MText]])

#_(m/validate
   MProperty
   {:id 1
    :symbol {:id 1
             :symbol "something"}
    :text {:id 99
           :text "other thing"}})

(def MContent
  [:map
   [:id int?]
   [:properties [:sequential MProperty]]])


(def table-model-mapping
  {"text" MText
   "symbol" MSymbol
   "property" MProperty
   "content" MContent})

(defn prismatic-to-malli [model-def]
  (->> model-def
       (map (fn [[key prismatic-validator]]
              [key (cond (= prismatic-validator s/Int)
                         int?

                         :else
                         (:pred-name prismatic-validator))]))
       (apply vector :map)))

(defn malli-to-prismatic [model-def]
  {:pre [(sequential? model-def)]}
  (if (= :map (first model-def))
    (->> (rest model-def)
         (map malli-to-prismatic)
         (into {}))
    (let [[key malli-validator] model-def]
      [key
       (cond (= malli-validator int?)
             s/Int

             (= malli-validator string?)
             s/Str

             :else
             (throw (js/Error. (str "no match for malli validator: "
                                    malli-validator))))])))
