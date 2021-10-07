(ns xcl.node-common
  (:require ["fs" :as fs]
            ["path" :as path]
            ["string-interp" :as interp]))

(defn path-exists? [p]
  (when p
    (.existsSync fs p)))

(defn path-join [& ps]
  (apply (aget path "join") ps))

(defn get-environment-substituted-path
  [raw-path]
  (interp raw-path (aget js/process "env")))

(defn slurp-file [file-path]
  (.readFileSync fs
                 (get-environment-substituted-path file-path)
                 "utf-8"))
