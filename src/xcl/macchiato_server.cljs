(ns xcl.macchiato-server
  (:require
   [taoensso.timbre :refer [info]]
   [macchiato.server :as http]
   [reitit.ring :as ring]
   [macchiato.middleware.params :as params]
   [xcl.env :as env]))

(defn handler [request callback]
  (callback {:status 200
             :body "hello machi"}))

(defn server []
  (info "starting server")
  (let [host "127.0.0.1"
        port 3001]
    (http/start
      {:handler handler
       :host    host
       :port    port
       :on-success (fn [& args]
                     (println args)
                     (info "macchiato started"))})))

(defn -main []
  (info "hello main"))
