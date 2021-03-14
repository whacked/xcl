(ns xcl.indexer.signaling
  (:require ["jayson" :as jayson]
            ["jayson/lib/generateRequest"
             ;; see https://github.com/tedeh/jayson/blob/master/lib/generateRequest.js
             :as jayson-generateRequest]))

(def $chokidar-status-update-topic "chokidarStatusUpdate")
(def $cache-status-update-topic "cacheStatusUpdate")
(def $jsonrpc-topic "jsonrpc")

(def $chokidar-add "add")
(def $chokidar-change "change")
(def $chokidar-unlink "unlink")

(def $search-text "searchText")

(defn make-json-rpc-request [method clj-params]
  (jayson-generateRequest
   method
   (clj->js clj-params)))

(defn make-message-sender [socketio-interface]
  (fn [topic clj-payload]
    ^js/Object
    (.emit socketio-interface
           $jsonrpc-topic
           (make-json-rpc-request
            (name topic)
            clj-payload))))
