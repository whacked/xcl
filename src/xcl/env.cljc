(ns xcl.env
  (:refer-clojure :exclude [get])
  (:require [shadow-env.core :as env]
            #?(:clj [clojure.edn :refer [read-string]])))

;; write a Clojure function that returns variables to expose to :clj and :cljs.
;; the function must accept one variable, the shadow-cljs build-state
;; (which will be `nil` initially, before compile starts)
#?(:clj
   (defn read-env [build-state]
     (let [default-conf (-> (clojure.java.io/file
                             (System/getProperty "user.dir")
                             "default-config.edn")
                            (slurp)
                            (read-string))
           maybe-config-file (clojure.java.io/file
                              (System/getProperty "user.dir")
                              "config.edn")
           config-data (merge default-conf
                              (when (.exists maybe-config-file)
                                (-> maybe-config-file
                                    (slurp)
                                    (read-string))))
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
