(ns xcl.xsv-interop
  (:require [xcl.common :refer
             [get-file-extension]]
            ["papaparse" :refer [parse]]))

(defn get-delimiter-from-file-extension [file-name]
  (if (= (get-file-extension file-name) "tsv")
    "\t"
    ","))

(defn parse-xsv [xsv-string delimiter use-headers?]
  (-> xsv-string
      (clojure.string/trim)
      (parse #js {:delimiter delimiter
                  :header use-headers?})
      (aget "data")
      (js->clj)))

(defn parse-to-rows [xsv-string delimiter on-complete]
  (-> (parse-xsv xsv-string delimiter false)
      (on-complete)))

(defn parse-to-records [xsv-string delimiter on-complete]
  (-> (parse-xsv xsv-string delimiter true)
      (on-complete)))
