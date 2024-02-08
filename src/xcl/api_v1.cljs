;; unimaginative namespace to put functions central interaction functions
(ns xcl.api-v1
  (:require ["fs" :as fs]
            [xcl.resource-resolution
             :refer [$resource-resolver-loader-mapping
                     load-by-resource-resolver]]
            [xcl.common :refer [get-file-extension]]
            [xcl.external :as ext]
            [xcl.node-common :refer
             [path-join path-exists? slurp-file]]
            [xcl.content-interop :as ci]
            [xcl.git-interop :as git]
            [xcl.core :as sc]
            [xcl.console :as console]
            [xcl.sqlite :as sqldb]))

(def $lookup-cache (atom {}))
(when @sqldb/$sqlite-db
  (sqldb/hydrate-atom-from-db! $lookup-cache @sqldb/$sqlite-db))

(defn load-from-directive [directive]
  (or (@$lookup-cache directive)
      (let [spec (sc/parse-link directive)
            full-content (slurp-file
                          (let [file-name (:resource-resolver-path spec)]
                            (if (clojure.string/starts-with? file-name "/")
                              file-name
                              (path-join (.cwd js/process)
                                         file-name))))]
        (ci/resolve-content spec full-content)
        ;; (sqldb/save-kv! directive (->json data))
        )))
