(ns xcl.schemas.tracked-file
  (:require [malli.core :as m]
            [malli.json-schema :as jscm]
            ))

(def TrackedFileMetadata
  ;; originally craftygraffy/src/craftygraffy/file_tracker.cljs:TrackedFileMetadata()
  [:map
   [:_id string?]
   [:name string?]
   [:filepath string?]
   [:mtime
    {:description "milliseconds since the epoch"}
    number?]
   [:size integer?]
   [:file-sha256 string?]
   [:parser-list [:sequential string?]]
   [:content-path sequential?]
   [:content-sha256 string?]
   [:tags set?]])

(comment
  (-> TrackedFileMetadata
      (jscm/transform)
      (clojure.pprint/pprint)))
