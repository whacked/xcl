(ns xcl.model-crud-frontend
  (:require
   [goog.dom :as gdom]
   [reagent.dom :as r]))

(defn setup-ui! []
  (r/render
   [(fn []
      [:div "hello reagent"])]
   (gdom/getElement "main")))

(defn -crud-main []
  (setup-ui!))

(-crud-main)
