(ns xcl.indexer.frontend
  (:require-macros
   [hiccups.core :as h])
  (:require
   [goog.dom :as gdom]
   [reagent.dom :as rdom]
   [reagent.ratom :as r]
   [hiccups.runtime]
   ["socket.io-client" :as sio]
   ["react-tabulator" :refer [ReactTabulator]]
   ["react-tabs" :refer [Tab Tabs TabList TabPanel]]
   ["color-hash" :as ColorHash]
   [xcl.indexer.signaling :as signaling]))

(defonce $state (r/atom {:chokidar-history []
                         :cache-history []}))

(defn render-chokidar-status-table [statuses]
  (let [
        ;; fixme
        payload-columns [:time :op :path]
        ]
    [:table
     [:tbody
      [:tr
       (->> payload-columns
            (map (fn [key]
                   ^{:key [:th key]}
                   [:th (name key)])))]
      (->> statuses
           (map-indexed
            (fn [i status]
              ^{:key [:tr i]}
              [:tr
               (->> payload-columns
                    (map-indexed
                     (fn [j key]
                       ^{:key [:td i j]}
                       [:td
                        (let [value (key status)]
                          (case key
                            :time (.toISOString (js/Date. value))
                            value))])))])))]]))

(defn render-cache-status-table [statuses]
  (let [
        ;; fixme
        payload-columns [:filepath :mtime]
        ]
    [:table
     [:tbody
      [:tr
       (->> payload-columns
            (map (fn [key]
                   ^{:key [:th key]}
                   [:th (name key)])))]
      (->> statuses
           (map-indexed
            (fn [i status]
              ^{:key [:tr i]}
              [:tr
               (->> payload-columns
                    (map-indexed
                     (fn [j key]
                       ^{:key [:td i j]}
                       [:td
                        (let [value (key status)]
                          (case key
                            :mtime (.toISOString (js/Date. value))
                            value))])))])))]]))

(defn setup-ui! []
  (let [sio-client (sio)
        send-message (signaling/make-message-sender sio-client)]

    (signaling/add-jsonrpc-handler
     signaling/$chokidar-status-update-topic
     (fn [clj-payload]
       (js/console.log "chokidar update!")
       (swap! $state update-in [:chokidar-history]
              (fn [current-history]
                (conj current-history clj-payload)))))
    
    (signaling/add-jsonrpc-handler
     signaling/$cache-status-update-topic
     (fn [clj-payload]
       (js/console.log "status update!")
       (swap! $state update-in [:cache-history]
              (fn [current-history]
                (conj current-history clj-payload)))))
    
    (signaling/add-jsonrpc-handler
     signaling/$search-text
     (fn [clj-payload]
       (swap! $state assoc-in [:search-results] clj-payload)))

    (doto sio-client
      (.on "disconnect"
           (fn [socket]
             (js/console.log "disconnected")))
      (signaling/bind-jsonrpc-processors!))
    
    (rdom/render
     (let [my-state (r/atom {})]
       [(fn []
          [:div
           [:h3 "searcher"]
           [:div
            [:form
             [:input
              {:type "text"
               :style {:width "100%"}
               :value (get-in @my-state [:search-text])
               :on-change (fn [evt]
                            (let [value (aget evt "target" "value")]
                              (swap! my-state assoc :search-text value)
                              (when (< 1 (count value))
                                (js/console.log "search out!: " value)
                                (send-message signaling/$search-text {:text value}))))}]]
            [:h4 "results"]
            [:table
             [:tbody
              (->> (get-in @$state [:search-results])
                   (map-indexed
                    (fn [i result]
                      ^{:key [:result i]}
                      [:tr
                       [:td
                        (pr-str result)]])))]]]

           [:> Tabs
            [:> TabList
             (->> ["tab1" "tab2"]
                  (sort)
                  (map (fn [table-name]
                         ^{:key ["tab" table-name]}
                         [:> Tab table-name])))]
          
            [:> TabPanel
             [(fn []
                [:div
                 [:h3 "cache statuses"]
                 [:div
                  (render-cache-status-table
                   (get-in @$state [:cache-history]))]]
                )]]

            [:> TabPanel
             [(fn []
                [:div
                 [:h3 "chokidar statuses"]
                 [:div
                  (render-chokidar-status-table
                   (get-in @$state [:chokidar-history]))
                  ]
                 ])]]]])])
     (gdom/getElement "main"))))

(defn -crud-main []
  (setup-ui!))

(-crud-main)
