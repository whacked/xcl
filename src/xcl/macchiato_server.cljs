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
   ;; NOTE env/get is compile-time
   [xcl.env :as env :refer [$config]]

   ["path" :as path]
   ["fs" :as fs]
   ["chalk" :as chalk]
   ["yaml" :as yaml]
   [xcl.database :as db]
   [cljs.reader]
   [promesa.core :as promesa]
   [xcl.seed-data-loader]
   [xcl.indexer.engine :as indexer]
   [garden.core :refer [css]]
   ["socket.io" :as socketio]
   [xcl.indexer.signaling :as signaling]
   [xcl.textsearch.engine :as textsearch]))

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
                           (css [:* {:margin 0
                                     :padding 0}])]
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
                                    (respond)))))}}]]
   
   ["/"
    {:get {:handler (fn [request respond _]
                      (-> $routes
                          (routes-to-help)
                          (respond)))}}]

   ["/indexer"
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
                          (->> ["tabulator-tables/dist/css/tabulator.min.css"
                                "react-tabulator/lib/styles.css"
                                "react-tabs/style/react-tabs.css"]
                               (map (fn [css-path]
                                      [:link
                                       {:rel "stylesheet"
                                        :href (str "/" $css-loader-endpoint "/" css-path)}])))
                          [:style
                           (css [:* {:margin 0
                                     :padding 0}])]]
                         [:body
                          [:div
                           [:h1 "indexer"]]
                          [:div {:id "main"}]
                          [:script
                           {:type "text/javascript"
                            :src (str "/" $js-loader-endpoint
                                      "/" (name $crud-frontend-main-module) ".js")}]]]
                        (h/html5)
                        (plain-text)
                        (assoc-in [:headers :content-type]
                                  "text/html")
                        (respond)))}}]]])

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

(defn setup-server []
  (info (str "starting server on port "
             (get-in $config [:indexer-port])))
  (http/start
   {:handler app ;; example-handler
    :host    (get-in $config [:indexer-host])
    :port    (get-in $config [:indexer-port])
    :on-success (fn [& args]
                  (info "macchiato started"))}))

(defn setup-socket-server [express-server]
  (let [socket-server
        (socketio express-server)]
    (doto socket-server
      (.on "connection"
           (fn [socket]
             (js/console.log "got connection")
             (signaling/bind-jsonrpc-processors! socket))))))

(def $bootstrap-data? true)
(defn -main []
  (js/console.log (.yellow
                   chalk
                   (str
                    (apply str (take 50 (repeat "=")))
                    " OK "
                    (apply str (take 50 (repeat "="))))))


  #_(db/initialize-database!
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
  
  (let [express-server (setup-server)
        socket-server (setup-socket-server express-server)
        
        send-message
        (signaling/make-message-sender socket-server)

        emit-status
        (fn [clj-payload]
          (send-message
           signaling/$chokidar-status-update-topic
           (assoc clj-payload
                  :time (-> (new js/Date)
                            (.getTime)))))]
    
    (signaling/add-jsonrpc-handler
     signaling/$search-text
     (fn [clj-payload]
       (prn clj-payload)
       (textsearch/search
        (:text clj-payload)
        (fn [results]
          (send-message
           signaling/$search-text
           results)))))
    
    (let [paths (get-in $config [:indexer-paths])]
      
      (indexer/initialize-file-watcher
       paths
       {signaling/$chokidar-add
        (fn [path]
          (emit-status
           {:op "ADD"
            :path path}))
        signaling/$chokidar-change
        (fn [path]
          (emit-status
           {:op "UPD"
            :path path}))
        signaling/$chokidar-unlink
        (fn [path]
          (emit-status
           {:op "DEL"
            :path path}))})

      (js/setTimeout
       (fn []
         (indexer/rebuild-file-info-cache!
          paths
          (fn [file-info]
            (info "sending info for file: "
                  (:filepath file-info))
            (send-message
             signaling/$cache-status-update-topic
             file-info))))
       2000))))
