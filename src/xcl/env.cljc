(ns xcl.env
  (:refer-clojure :exclude [get])
  (:require [shadow-env.core :as env]
            #?(:clj [clojure.edn :refer [read-string]])
            
            #?(:cljs ["fs" :as fs])
            #?(:cljs ["path" :as path])
            #?(:cljs [cljs.reader :refer [read-string]])))

(defn load-edn [file-path]
  #?(:clj
     (-> file-path
         (slurp)
         (read-string)))
  #?(:cljs
     (-> (.readFileSync fs file-path "utf-8")
         (cljs.reader/read-string))))

(def $config
  #?(:clj
     (let [default-conf (-> (clojure.java.io/file
                             (System/getProperty "user.dir")
                             "default-config.edn")
                            (load-edn))
           maybe-config-file (clojure.java.io/file
                              (System/getProperty "user.dir")
                              "config.edn")]
       (merge default-conf
              (when (.exists maybe-config-file)
                (load-edn maybe-config-file)))))
  #?(:cljs
     (let [base-dir (.cwd js/process)
           default-conf (load-edn (.join path base-dir "default-config.edn"))
           maybe-config-file (.join path base-dir "config.edn")]
       (merge default-conf
              (when (.existsSync fs maybe-config-file)
                (load-edn maybe-config-file))))))

;; write a Clojure function that returns variables to expose to :clj and :cljs.
;; the function must accept one variable, the shadow-cljs build-state
;; (which will be `nil` initially, before compile starts)
#?(:clj
   (defn read-env [build-state]
     (let [config-data $config
           out {:common {:user.dir (System/getProperty "user.dir")}
                :clj    {}
                :cljs   config-data}]
       out)))

;; define & link a new var to your reader function.
;; you must pass a fully qualified symbol here, so syntax-quote (`) is useful.
;; in this example I use `get` as the name, because we can call Clojure maps
;; as functions, I like to alias my env namespace as `env`, and (env/get :some-key)
;; is very readable.
(env/link get `read-env)
