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
   [xcl.data-model :as model]
   [xcl.env :as env]
   ["path" :as path]
   ["fs" :as fs]
   ["chalk" :as chalk]
   ["yaml" :as yaml]
   [xcl.database :as db]
   [cljs.reader]
   [promesa.core :as promesa]
   [xcl.seed-data-loader]
   ))

(def $working-dir (.cwd js/process))
(def $shadow-config
  (-> (.readFileSync
       fs
       (.join path $working-dir "shadow-cljs.edn")
       "utf-8")
      (cljs.reader/read-string)))

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
                      (info (.red chalk (str "WARNING: skipped: >>>" route "<<<")))))))))

(defn plain-text [body]
  {:status 200
   :body body})

(defn plain-file [file-path]
  (if (.existsSync fs file-path)
    (plain-text (.readFileSync fs file-path "utf-8"))
    {:status 404
     :body (str "not found: " file-path)}))

(defn make-json-response [data]
  (-> data
      (clj->js)
      (js/JSON.stringify)
      (plain-text)
      (assoc-in [:headers :content-type] "application/json")))

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
       (h/html5)
       (plain-text)))

(def $css-loader-endpoint "css")
(def $js-loader-endpoint
  (get-in $shadow-config [:builds :crud-frontend :output-to]))
(def $crud-frontend-main-module
  (-> (get-in $shadow-config [:builds :crud-frontend :modules])
      (keys)
      (first)))

(def $routes
  [""
   ["/help"
    {:get {:handler (fn [request respond _]
                      (-> $routes
                          (routes-to-help)
                          (respond)))}}]

   ["/data-model"
    {:get {:handler (fn [request respond _]
                      (->> model/table-model-mapping
                           (map (fn [[table-name model-def]]
                                  [(str "MODEL " table-name)
                                   (malli.json-schema/transform
                                    model-def)]))
                           (into {})
                           (clj->js)
                           (.stringify yaml)
                           (plain-text)
                           (respond)))}}]

   [(str "/" $css-loader-endpoint)
    ["/*.css"
     {:get {:handler (fn [request respond _]
                       (-> (or (when-let [target-file-name
                                          (get-in request [:path-params :.css])]
                                 (let [css-path
                                       (.join path "node_modules" target-file-name)]
                                   (plain-file css-path)))
                               {:status 400
                                :body (str "bad request")})
                           (respond)))}}]]

   [(str "/" $js-loader-endpoint)
    ["/*.js" {:handler (fn [request respond _]
                         (-> (or (when-let [target-file-name
                                            (get-in request [:path-params :.js])]
                                   (let [js-path (.join path
                                                        $working-dir
                                                        $js-loader-endpoint
                                                        target-file-name)]
                                     (plain-file js-path)))
                                 {:status 400
                                  :body (str "bad request")})
                             (assoc-in [:headers :content-type] "application/javascript")
                             (respond)))}]]

   ["/file"
    ["/:path"
     {:get {:handler (fn [request respond _]
                       (when-let [file-path (get-in request [:path-params :path])]
                         (-> (plain-file file-path)
                             (assoc-in [:headers :content-type] "text/plain")
                             (respond))))}}]]

   ["/crud"
    [""
     {:get {:handler (fn [request respond _]
                       (->
                        [:html
                         [:head
                          [:meta
                           {:content "text/html;charset=utf-8"
                            :http-equiv "Content-Type"}]
                          [:meta
                           {:content "utf-8"
                            :http-equiv "encoding"}]
                          [:style
                           "* { margin: 0; padding: 0; }"]
                          (->> ["tabulator-tables/dist/css/tabulator.min.css"
                                "react-tabulator/lib/styles.css"
                                "react-tabs/style/react-tabs.css"]
                               (map (fn [css-path]
                                      [:link
                                       {:rel "stylesheet"
                                        :href (str "/" $css-loader-endpoint "/" css-path)}])))]
                         [:body
                          [:div {:id "main"}]
                          [:script
                           {:type "text/javascript"
                            :src (str "/" $js-loader-endpoint
                                      "/" (name $crud-frontend-main-module) ".js")}]]]
                        (h/html5)
                        (plain-text)
                        (assoc-in [:headers :content-type]
                                  "text/html")
                        (respond)))}}]

    ["/:model"
     {:response-format :json
      :get {:handler (fn [request respond _]
                       (let [model-name (get-in request [:path-params :model])]
                         (cond (not (db/has-table? model-name))
                               (respond {:status 404
                                         :body (str "not such model: " model-name)})

                               (= model-name "content")
                               (db/load-content
                                (fn [results]
                                  (-> results
                                      (make-json-response)
                                      (respond))))

                               :else
                               (db/find-by-attribute-all
                                model-name
                                true
                                (fn [results]
                                  (-> results
                                      (make-json-response)
                                      (respond)))))))}
      :post {:handler (fn [request respond _]
                        (let [model-name (get-in request [:path-params :model])
                              payload (get-in request [:params :body-params])]

                          (cond (not (db/has-table? model-name))
                                (-> {:status 404
                                     :body (str "not such model: " model-name)}
                                    (respond))

                                (#{"text" "symbol"} model-name)
                                (db/update-record
                                 model-name
                                 payload
                                 (fn [result]
                                   (-> result
                                       (make-json-response)
                                       (respond))))
                                
                                :else
                                (-> {:status 400
                                     :body "unsupported request"}
                                    (respond)))))}}]]])

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

(def $bootstrap-data? true)
(defn -main []
  (db/initialize-database!
   db/$default-settings
   (fn [builder]
     
     (if $bootstrap-data?
       
       (xcl.seed-data-loader/bootstrap-from-content-props!
        @db/$builder (:content-props xcl.seed-data-loader/example-data-input))

       (let [tables
             ;; specify order explicitly to avoid contention from table dependency
             ["symbol" "text"
              "property"
              "content"
              "content__property"]
             
             iter-create-seed-data!
             (fn iter-create-seed-data! [remain]
               (when (seq remain)
                 (let [table-name (first remain)
                       seed-data (xcl.seed-data-loader/example-seed-data table-name)
                       insert-query
                       (db/generate-insert-query
                        builder table-name seed-data)]
                   (promesa/do!
                    (db/run-sql! builder insert-query)
                    (iter-create-seed-data! (rest remain))))))]
         (iter-create-seed-data! tables)))))
  
  (server))
