(ns xcl.macchiato-server
  (:require-macros
   [hiccups.core :as h])
  (:require
   [hiccups.runtime]
   [taoensso.timbre :refer [info]]
   [macchiato.server :as http]
   [reitit.ring :as ring]
   [macchiato.middleware.params :as params]
   [reitit.ring.coercion :as rrc]
   [macchiato.middleware.restful-format :as rf]
   [malli.json-schema]
   [xcl.env :as env]
   ["chalk" :as chalk]))


(defn derive-all-routes [routes & [parent-chain]]
  {:pre []}
  (->> routes
       (map (fn [route]
              (cond (and (sequential? route)
                         (string? (first route)))
                    (let [endpoint (first route)
                          breadcrumbs (-> parent-chain
                                          (vec)
                                          (conj endpoint))
                          [handlers
                           subroutes]
                          (if (map? (second route))
                            [(second route) (drop 2 route)]
                            [{} (rest route)])]
                      (->> (conj
                            (derive-all-routes subroutes breadcrumbs)
                            [breadcrumbs
                             (->> (keys handlers)
                                  (filter #{:get :post :put :delete :options})
                                  (set))])
                           (into {})))
                    
                    (and (sequential? route)
                         (sequential? (first route)))
                    (derive-all-routes route parent-chain)
                    :else
                    (do
                      (println (.yellow chalk (str "WARNING: skipped " route)))))))))

(defn plain-text [body]
  {:status 200
   :body body})

(declare $routes)
(defn routes-to-help [& _]
  (->> (derive-all-routes $routes)
       (apply concat)
       (into {})
       (sort)
       (map (fn [[breadcrumbs methods]]
              (let [full-path (apply str breadcrumbs)
                    allowed-methods (->> methods
                                         (map name)
                                         (map clojure.string/upper-case)
                                         (interpose ", ")
                                         (apply str))]
                [:li
                 (if (methods :get)
                   [:a {:href full-path} full-path]
                   [:span full-path])
                 " "
                 [:code allowed-methods]])))
       (apply vector :div)
       (h/html)
       (plain-text)))

(def $routes
  [""
   ["/help"
    {:get {:handler (fn [request respond _]
                      (-> $routes
                          (routes-to-help)
                          (respond)))}}]])

(defn wrap-body-to-params
  [handler]
  (fn [request respond raise]
    (handler
      (-> request
        (assoc-in [:params :body-params] (:body request))
        (assoc :body-params (:body request))) respond raise)))

(def app
  (ring/ring-handler
   (ring/router
      [$routes]
      {:data {:middleware [params/wrap-params
                           (fn [handler]
                             (rf/wrap-restful-format
                               handler
                               {:keywordize? true}))
                           wrap-body-to-params
                           rrc/coerce-request-middleware
                           rrc/coerce-response-middleware
                           ]}})
   (ring/create-default-handler)))

(defn example-handler [request callback]
  (callback {:status 200
             :body "hello macchiato"}))

(defn server []
  (info "starting server")
  (let [host "127.0.0.1"
        port 3000]
    (http/start
      {:handler app  ;; example-handler
       :host    host
       :port    port
       :on-success (fn [& args]
                     (info "macchiato started"))})))

(defn -main []
  (server))

