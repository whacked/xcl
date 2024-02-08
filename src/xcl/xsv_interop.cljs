(ns xcl.xsv-interop
  (:require [xcl.common :refer
             [get-file-extension]]
            [xcl.content-interop :as ci]
            ["papaparse" :refer [parse]]))

(defn get-delimiter-from-file-extension [file-name]
  (if (= (get-file-extension file-name) "tsv")
    "\t"
    ","))

(defn -parse-xsv [xsv-string delimiter use-headers? on-complete]
  (-> xsv-string
      (parse #js {:delimiter delimiter
                  :header use-headers?})
      (aget "data")
      (js->clj)
      (on-complete)))

(defn parse-to-rows [xsv-string delimiter on-complete]
  (-parse-xsv xsv-string delimiter false on-complete))

(defn parse-to-records [xsv-string delimiter on-complete]
  (-parse-xsv xsv-string delimiter true on-complete))

(defn resolve-xsv-string
  "convenience method to reduce duplication;
  this is used across:
  - xcl.node-interop  (for node)
  - xcl.external-js-content  (for browser)
  so it takes the resource name with the actual *file content*;
  the resource name is only used to determine the delimiter
  "
  [xsv-string
   resource-address
   on-resolved]

  (let [file-name (:resource-resolver-path resource-address)
        delimiter (get-delimiter-from-file-extension file-name)]
    (when-let [{:keys [bound type]}
               (-> (get-in resource-address [:content-resolvers])
                   (first))]
      (case type
        :excel-a1 (parse-to-rows
                   xsv-string
                   delimiter
                   (fn [rows]
                     (-> rows
                         (ci/query-by-excel-a1-locator bound)
                         (on-resolved))))

        :jq-record-locator (parse-to-records
                            xsv-string
                            delimiter
                            (fn [records]
                              (-> records
                                  (ci/query-by-jq-record-locator bound)
                                  (on-resolved))))

        (on-resolved {:error (str "failed to constrict using: "
                                  bound)})))))
