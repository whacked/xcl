(ns xcl.sqlite
  (:require ["sqlite3" :as sqlite3]
            ["yesql" :as yesql]
            ["path" :as path]
            [xcl.node-common :refer
             [path-exists? path-join]]
            [xcl.env :as env :refer [$config]]))

(def $sql
  (yesql (path-join (.cwd js/process) "src")))

(defn filename-glob-to-query [filename]
  (-> (.parse path filename)
      (aget "name")
      (clojure.string/lower-case)
      (clojure.string/replace #"\*" "%")))

(defonce $sqlite-db
  (when-let [local-storage-db-path (get-in $config [:local-storage])]
    (js/console.info "using for local cache: " local-storage-db-path)
    (atom
     (doto (new sqlite3/Database local-storage-db-path)
       (.run "CREATE TABLE IF NOT EXISTS kv (key TEXT UNIQUE, value TEXT)")))))

(defn hydrate-atom-from-db! [cache-atom db]
  ^js/Object
  (.each db "SELECT key, value FROM kv"
         (fn [err row]
           #_(js/console.log err row)
           (swap! cache-atom assoc
                  (aget row "key")
                  (-> (aget row "value")
                      (js/JSON.parse))))))

(defn save-kv! [key-string value-json-string]
  (when-let [db @$sqlite-db]
    (-> ^js/Object (.prepare db "INSERT OR IGNORE INTO kv VALUES (?, ?)")
        ^js/Object (.run key-string value-json-string))))
