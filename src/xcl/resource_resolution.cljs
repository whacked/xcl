(ns xcl.resource-resolution
  (:require [xcl.content-interop :as ci]
            [xcl.console :as console]
            [xcl.external :as ext]
            [xcl.common :refer [get-file-extension]]
            [xcl.zotero-interop :as zotero]
            [xcl.calibre-interop :as calibre]))

;; NOTE: looks like these are a counterpart to ci/$resolver
;;       as they support async loading via callback
(def $resource-resolver-loader-mapping
  (atom {:calibre-file
         (fn [spec callback]
           (calibre/load-text-from-epub
            (str "*" (:resource-resolver-path spec) "*.epub")
            spec
            (fn [text]
              (js/console.log (str "calibre epub: " (count text) " bytes\n\n"))
              (->> text
                   (clojure.string/trim)
                   (assoc spec :text)
                   (clj->js)
                   (callback nil)))))

         :zotero-file
         (fn [spec callback]
           (zotero/load-text-from-file
            (str "*" (:resource-resolver-path spec) "*")
            spec
            (fn [text & [page]]
              (js/console.log
               (str "zotero loaded:\n"
                    (when page
                      (str "page: " page "\n"))
                    (count text)
                    " bytes\n\n"))
              (->> (if text (clojure.string/trim text) "")
                   (assoc spec :text)
                   (clj->js)
                   (callback nil)))
            (fn [err]
              (js/console.error err))))

         :exact-name
         (fn [spec callback]
           (let [extension (get-file-extension
                            (:resource-resolver-path
                             spec))]
             (when-let [external-loader (@ext/$ExternalLoaders extension)]
               (println "!!! loading for extension " extension
                        "\n" spec " --using--> " external-loader)
               (external-loader
                spec
                (fn [text]
                  (some->> (ci/resolve-content spec text)
                           (clojure.string/trim)
                           (assoc spec :text)
                           (clj->js)
                           (callback nil)))))))}))

(defn load-by-resource-resolver [spec callback]
  (if-let [loader (@$resource-resolver-loader-mapping
                   (:resource-resolver-method spec))]
    (loader spec callback)
    (do
      (js/console.warn (console/red "FAILED TO LOAD RESOLVER FOR "
                                    (str spec))
                       " available resolvers:")
      (doseq [key (keys @$resource-resolver-loader-mapping)]
        (js/console.warn (str "- " key))))))
