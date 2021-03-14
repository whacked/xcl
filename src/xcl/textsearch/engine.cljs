(ns xcl.textsearch.engine
  (:require ["flexsearch" :as flexsearch]
            [taoensso.timbre :refer [info debug warn error]]))

(defonce $index (.create flexsearch))

(defn add-to-corpus [id text]
  (info (str "adding corpus for [" id "] (" (count text) ") bytes"))
  (.add $index id text))

(defn search [term on-results]
  (.search $index term on-results))
