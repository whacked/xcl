(ns xcl.indexer.signaling
  (:require ["jayson/lib/generateRequest"
             ;; see https://github.com/tedeh/jayson/blob/master/lib/generateRequest.js
             :as jayson-generateRequest]
            [taoensso.timbre :refer [info debug warn error]]))

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
    (debug ">> [JSONRPC OUT]" topic)
    
    ^js/Object
    (.emit socketio-interface
           $jsonrpc-topic
           (make-json-rpc-request
            (name topic)
            clj-payload))))

(defonce $jsonrpc-handlers (atom {}))
(defn add-jsonrpc-handler [method handler-func]
  (swap! $jsonrpc-handlers assoc method handler-func))

(defn bind-jsonrpc-processors! [socketio-interface]
  ^js/Object
  (.on
   socketio-interface
   $jsonrpc-topic
   (fn [js-payload]
     (debug "<< [JSONRPC IN]" (aget js-payload "method"))
     (let [clj-payload (js->clj js-payload :keywordize-keys true)
           {:keys [method params]} clj-payload]
       (if-let [handler-func (@$jsonrpc-handlers method)]
         (handler-func params)
         (do
           (error
            {:error (str "no handler for <" method ">")})))))))
