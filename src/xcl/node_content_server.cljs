(ns xcl.node-content-server
  (:require
   ["express" :as express]
   ["jayson" :as jayson]
   ["cors" :as cors]
   ["body-parser" :rename {json json-parser}]
   ["node-ipc" :as node-ipc]
   [xcl.common :refer [get-file-extension]]
   
   ;; from test.cljs
   ["sqlite3" :as sqlite3]
   ["yesql" :as yesql]
   ["fs" :as fs]
   ["path" :as path]
   ["child_process" :as child-process]
   [xcl.core :as sc]
   [xcl.content-interop :as ci]
   [xcl.external :as ext]
   [xcl.pdfjslib-interop :as pdfjslib]
   [xcl.node-epub-interop :as epubi]
   [xcl.node-interop :as ni]
   [xcl.node-common :refer
    [path-join path-exists? slurp-file]]
   [xcl.calibre-interop :as calibre]
   [xcl.zotero-interop :as zotero]
   [xcl.git-interop :as git]
   [xcl.console :as console]
   
   [xcl.indexer.engine :as indexer]
   [xcl.textsearch.engine :as textsearch]
   [xcl.indexer.signaling :as signaling]
   
   [xcl.env :as env :refer [$config]]))

(def $JSONRPC-PORT (env/get :jsonrpc-port))
(def $XCL-NO-CACHE "xcl-no-cache")
(def $XCL-SERVER-ID "xcl-server")

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

(defn open-file-natively [file-path]
  (let [open-command (str
                      (case (aget js/process "platform")
                        "win64" "open"
                        "darwin" "open"
                        "xdg-open")
                      " "
                      "\""
                      file-path
                      "\"")]
    (println "INVOKE: " open-command)
    (js-invoke
     child-process "exec" open-command)))

(def $request-cache (atom {}))
(defn cache-log [& ss]
  (js/console.info
   (apply str
          (console/cyan "[CACHE-CONTROL] ")
          ss)))

(defn wrap-cached [req-mapping]
  (->> req-mapping
       (map (fn [[method-key original-handler]]
              (cache-log "wrapping request cache for " method-key)
              [method-key
               (fn [args context original-callback]
                 
                 (if (some->> (aget context "headers" $XCL-NO-CACHE)
                              (clojure.string/lower-case)
                              (#{"yes" "true" "1"}))
                   (do
                     (cache-log (console/red (str "bypassing cache for " args)))
                     (original-handler args context original-callback))
                   
                   (let [cache-key (pr-str args)
                         maybe-cached-response (@$request-cache cache-key)]
                     (cache-log "checking cache key: " cache-key)
                     (if-not (empty? maybe-cached-response)
                       (do
                         (cache-log (console/green "returning cached response for " cache-key))
                         (apply original-callback maybe-cached-response))
                       (do
                         (cache-log (console/red "running original handler for " cache-key))
                         (original-handler
                          args
                          context
                          (fn wrapped-callback [err-response ok-response]
                            (cache-log (console/yellow "caching response for " cache-key))
                            (swap! $request-cache
                                   assoc cache-key [err-response ok-response])
                            (original-callback err-response ok-response))))))))]))
       (into {})))

;;add jsonrpc request cache wrapper fn
(def $handler-mapping
  ;; see https://github.com/tedeh/jayson#client-callback-syntactic-sugar
  ;; on callback structure; in short:
  ;; (callback Error Result)

  (wrap-cached
   {:echo (fn [args context callback]
            (println (js->clj args :keywordize-keys true))
            (-> args (js->clj) (println))
            (callback nil args))
  
    :get-text (fn [args context callback]
                (let [{:keys [protocol directive]}
                      (js->clj args :keywordize-keys true)]

                  ((case protocol

                     ("file" "xcl")
                     (fn [directive callback]
                       ;; directive is e.g.
                       ;; "xcl:./public/tracemonkey.pdf?p=3&s=Monkey observes that...so TraceMonkey attempts"
                       (let [resource-spec (sc/parse-link directive)
                             resolved-resource-path (:resource-resolver-path
                                                     resource-spec)
                             extension (get-file-extension
                                        resolved-resource-path)
                             resolve-content-and-return!
                             (fn [text]
                               (some->> (ci/resolve-content resource-spec text)
                                        (assoc resource-spec :text)
                                        (clj->js)
                                        (callback nil)))]
                         (println "loading for extension " extension
                                  "\n" resource-spec)
                         (if-let [external-loader (@ext/$ExternalLoaders extension)]
                           (external-loader
                            resource-spec
                            resolve-content-and-return!)

                           (if-not (path-exists? resolved-resource-path)
                             ;; error response structure is not standardized
                             (callback {:status "error"
                                        :message (str "could not retrieve " resolved-resource-path)}
                                       nil)
                             (.readFile fs
                                        resolved-resource-path
                                        "utf-8"
                                        (fn [err text]
                                          (resolve-content-and-return! text)))))))

                     ("git")
                     (fn [directive callback]
                       (let [resource-spec (sc/parse-link directive)
                             gra (-> resource-spec
                                     (:link)
                                     (git/parse-git-protocol-blob-path))]
                         
                         (git/resolve-git-resource-address
                          gra
                          (fn [full-content]
                            (some->> (ci/resolve-content resource-spec full-content)
                                     (assoc resource-spec :text)
                                     (clj->js)
                                     (callback nil)))
                          (fn [_]
                            (some->> {:status "failed"}
                                     (clj->js)
                                     (callback nil))))))
                   
                     ("calibre" "zotero")
                     (fn [directive callback]
                       (let [resource-spec (sc/parse-link directive)]
                         (load-by-resource-resolver
                          resource-spec
                          callback)))

                     (fn [& _]
                       (callback nil {:message (str "failed to process directive "
                                                    directive)})))
                 
                   directive callback)))
  
    :open (fn [args callback]
            (let [{:keys [protocol directive]}
                  (js->clj args :keywordize-keys true)
                  resource-spec (sc/parse-link directive)
                  resolved-path (:resource-resolver-path
                                 resource-spec)
                  complete-request
                  (fn [file-path]
                    (->> {:status (or (when (path-exists? file-path)
                                        (open-file-natively
                                         file-path)
                                        "ok")
                                      "error")}
                         (clj->js)
                         (callback nil)))]
            
              (case protocol
                "calibre"
                (calibre/find-matching-epub
                 (str "*" resolved-path "*.epub")
                 complete-request)
              
                "zotero"
                (zotero/find-matching-file
                 (str "*" resolved-path "*")
                 complete-request)
              
                ;; generic
                (complete-request resolved-path))))

    (keyword signaling/$search-text)
    (fn [js-args context jayson-callback]
      (textsearch/search
       (aget js-args "text")
       (fn [results]
         (jayson-callback nil results))))}))

(defn start-server! [jsonrpc-port]
  ;; ref https://github.com/tedeh/jayson#server-cors
  (let [app (express)
        server (-> jayson
                   (.server (clj->js $handler-mapping)
                            #js {"useContext" true}))]
    
    (doto app
      (.use (cors))
      (.use (json-parser))
      (.use (fn [req res next]
              ;; ref https://github.com/tedeh/jayson#server-context
              (let [context #js {"headers" (aget req "headers")}]
                (.call server
                       (aget req "body")
                       context
                       (fn [err result]
                         (when err (next err))
                         (.send res (or result #js {})))))))
      (.listen jsonrpc-port))))

(defn start-socket-server! [ipc-server-id]
  ;; DEBUGGING:
  ;; ref https://superuser.com/a/576404
  ;; mv /tmp/app.xcl-server /tmp/app.xcl-server.orig
  ;; socat -t100 -x -v UNIX-LISTEN:/tmp/app.xcl-server,mode=777,reuseaddr,fork UNIX-CONNECT:/tmp/app.xcl-server.orig
  ;; mv /tmp/app.xcl-server.orig /tmp/app.xcl-server
  
  (let [get-server (fn [] (aget node-ipc "server"))]
    (aset node-ipc "config" "id" ipc-server-id)
    (aset node-ipc "config" "retry" 1500)
    (.serve
     node-ipc
     (fn []
       (doto ^js/Object (get-server)
         (.on "jsonrpc"
              (fn [data socket]
                (js/console.log "jsonrpc payload" data)
                (if-let [handler (-> (aget data "method")
                                     (keyword)
                                     ($handler-mapping))]
                  (let [fake-http-request-context #js {:headers {}}
                        respond
                        (fn respond [_error_ success-js-payload]
                          ^js/Object
                          (.emit (get-server) socket "jsonrpc" success-js-payload))]
                    (handler (aget data "params")
                             fake-http-request-context
                             respond))

                  (js/console.error "no handler for this payload")))))))
    
    (-> (get-server)
        (.start))))

(defn -main []
  (let [cmd-line-arguments (aget js/process "argv")
        _node-bin-path (first cmd-line-arguments)
        _node-script-path (second cmd-line-arguments)
        cli-argument-map (->> (drop 2 (js->clj cmd-line-arguments))
                              (partition 2)
                              (map vec)
                              (into {}))]
    (or
     (when-let [bind-target (cli-argument-map "--bind")]
       (cond (re-find #"^\d+$" bind-target)
             (do
               (js/console.log "setting jsonrpc server port to"
                               bind-target)
               (start-server! (js/parseInt bind-target)))
            
             (= bind-target "socket")
             (do
               (js/console.log "attempting to start ipc socket server")
               (start-socket-server! $XCL-SERVER-ID))

             :else nil))

     (do
       (js/console.log "using default jsonrpc server port"
                       $JSONRPC-PORT)
       (start-server! $JSONRPC-PORT)

       (let [paths (get-in $config [:indexer-paths])]
         
         ;; TODO: add file watcher
         ;;       generalize the watcher+index-updater
         ;;       to work for macchiato + node + electron
         ;; see: macchiato-server:main()
         
         (indexer/rebuild-file-info-cache! paths))))))
