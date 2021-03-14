(ns xcl.indexer.engine
  (:require [malli.core :as m]
            [malli.generator :as mg]
            [xcl.schemas.tracked-file
             :refer [TrackedFileMetadata]]
            ["chokidar" :as chokidar]
            ["path" :as node-path]
            ["fs" :as fs]
            ["sqlite3" :as sqlite3]
            ["glob" :as glob]
            [taoensso.timbre :refer [info debug warn error]]
            [xcl.indexer.signaling :as signaling]
            [xcl.textsearch.engine :as textsearch]))


(defonce $global-watcher (atom nil))
(defonce $cache-path "/tmp/tester/cache.db")
(defonce $global-cache-atom (atom {}))
;; (defonce $memdb-atom (atom nil))
(defonce $cache-db (atom {}))


(def CacheEntrySchema
  [:map
   [:path
    {:description "abspath"
     :primary-key true}
    string?]
   [:hash string?]
   [:mtime
    {:description "milliseconds since the epoch"}
    number?]])


(defn schema-to-sqlite-table [table-name basic-schema]
  (let [column-declarations
        (->> (rest basic-schema)
             (map (fn [schema-entry]
                    (let [field-name (first schema-entry)
                          is-primary-key? (and
                                           (map? (second schema-entry))
                                           (get-in (second schema-entry)
                                                   [:primary-key]))
                          field-type (last schema-entry)]
                      (str (name field-name)
                           " "
                           (cond (= string? field-type)
                                 "TEXT"
                           
                                 (= int? field-type)
                                 "INTEGER"
                           
                                 (= number? field-type)
                                 "REAL")
                           (when is-primary-key?
                             " PRIMARY KEY"))))))]
    (str
     "CREATE TABLE IF NOT EXISTS "
     table-name " (\n"
     (->> column-declarations
          (map (fn [statement]
                 (str "  " statement ",\n")))
          (apply str))
     ")")))

(def $cache-table-name "cache1")
(defn create-tables! [db]
  (let [sql (schema-to-sqlite-table
             $cache-table-name
             CacheEntrySchema)]
    (try
      ^js/Object
      (.run db sql)
      (catch js/Error e
        (error
         (str "ERROR: could not create tables:\n"
              e "\n"
              sql))))))

(defn open-database [database-path]
  (try
    (new sqlite3/Database database-path)
    (catch js/Error e
      (error
       (str "ERROR: could open not database at "
            database-path ":\n" e)))))

(defn serialize-object [object]
  (-> object
      (clj->js)
      (js/JSON.stringify)))

(defn deserialize-object [json]
  (-> json
      (js/JSON.parse)
      (js->clj :keywordize-keys true)))

(defn load-cache!
  ([] (load-cache! $cache-path))
  ([cache-path]
   ;; (when (nil? @$memdb-atom)
   ;;   (info "loading in-memory database")
   ;;   (reset! $memdb-atom (open-database ":memory:")))
   
   (let [is-new-database? (not (.existsSync fs cache-path))
         db (open-database cache-path)]
     
     (when is-new-database?
       (info "creating a new database file")
       (create-tables! db))
     
     (info "loading existing database into memory")
     (doto db
       (.all
        (str
         "SELECT * FROM " $cache-table-name)
        (fn [err js-rows]
          (when err (error err))
          (doseq [row js-rows]
            (info row)
            #_(->> js-rows
                   (array-seq)
                   (map (fn [row]
                          (let [cache-data (deserialize-object row)]
                            (js/console.log row)
                            #_(swap! $global-cache-atom)
                            )))))))
       (.close)))))

(defn save-cache!
  ([] (save-cache! $cache-path))
  ([cache-path]
   ))

(defn process-file [path]
  (let [fstats (->> (.openSync fs path)
                    (.fstatSync fs))
        file-content (.readFileSync fs path #js {:encoding "utf8" :flag "r"})]
    (textsearch/add-to-corpus path file-content)
    (-> (mg/generate TrackedFileMetadata)
        (assoc :filepath path)
        (assoc :mtime (aget fstats "mtimeMs")))))

(defn is-directory? [abspath]
  (->> abspath
       (.lstatSync fs)
       (.isDirectory)))

(defn rebuild-file-info-cache! [paths & [on-file-info]]
  (info "rebuilding cache..."
        (str paths))
  (->> paths
       (filter (fn [path]
                 (.existsSync fs path)))
       (map (fn [path]
              (let [start-path-glob (.join node-path path "**/*")]
                (info (str "cache processor: " start-path-glob))
                (glob start-path-glob
                      (fn [err glob-fullpaths]
                        (doseq [fullpath glob-fullpaths]
                          (when-not (is-directory? fullpath)
                            (let [file-info (process-file fullpath)]
                              (when on-file-info
                                (on-file-info file-info))
                              (swap! $cache-db assoc fullpath file-info)))))))))
       (doall)))

(defn create-new-indexer
  [paths event-handlers]
  {:pre [(every? string? paths)
         (every?
          identity
          (map
           (fn [chokidar-event-key]
             (get event-handlers chokidar-event-key))
           [signaling/$chokidar-add
            signaling/$chokidar-change
            signaling/$chokidar-unlink]))]}
  (let [watcher (.watch chokidar (apply array paths))]
    (doto watcher
      (.on signaling/$chokidar-add
           (event-handlers signaling/$chokidar-add))
      (.on signaling/$chokidar-change
           (event-handlers signaling/$chokidar-change))
      (.on signaling/$chokidar-unlink
           (event-handlers signaling/$chokidar-unlink)))))

(defn initialize-file-watcher [start-paths event-handlers]
  
  (let [base-dir (.cwd js/process)
        paths [(.join node-path base-dir "src/xcl/indexer")
               "/tmp/foofoo/"]]

    (when @$global-watcher
      (.close @$global-watcher))

    (reset!
     $global-watcher
     (create-new-indexer start-paths event-handlers))
    
    ;; (cljs.pprint/pprint @$global-watcher)

    #_(js/setInterval
       #(do
          (let [watched
                (.getWatched @$global-watcher)
            
                abspaths
                (loop [remain-dirs
                       (array-seq (js/Object.keys watched))
                       out []]
                  (if (empty? remain-dirs)
                    out
                    (let [dir (first remain-dirs)]
                      (recur (rest remain-dirs)
                             (concat
                              out (->> (aget watched dir)
                                       (array-seq)
                                       (map (fn [relpath]
                                              (.join node-path dir relpath)))
                                       (filter
                                        (fn [abspath]
                                          (->> abspath
                                               (.lstatSync fs)
                                               (.isDirectory)
                                               (not))))))))))]

            (js/console.log (new js/Date))
            (rebuild-file-info-cache! abspaths)
            ))
     
       1000)
    ))
