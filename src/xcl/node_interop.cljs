(ns xcl.node-interop
  (:require ["pdfjs-dist" :as pdfjsLib]
            ["fs" :as fs]
            ["path" :as path]
            ["js-yaml" :as yaml]
            ["jsonpath-plus" :as JSONPath]

            [xcl.common :refer [get-file-extension]]
            [xcl.git-interop :as git]
            [xcl.core :as sc :refer [render-transclusion]]
            [xcl.content-interop :as ci]
            [xcl.external :as ext]
            [xcl.pdfjslib-interop
             :refer [pdfjslib-load-text
                     set-pdfjslib!]
             :as pdfi]
            [xcl.api-v1 :as api]
            [xcl.node-epub-interop :as epubi]
            [xcl.xsv-interop :as xsv]
            [xcl.node-common :refer
             [path-join path-exists? slurp-file]]
            [xcl.node-common :refer
             [path-exists? path-join
              get-environment-substituted-path]]
            [xcl.sqlite :as sqldb]))

;;;;;;;;;;;;;;;
;; epub, pdf ;;
;;;;;;;;;;;;;;;
(set-pdfjslib! pdfjsLib)
(ext/register-loader!
 "pdf"
 (fn [resource-address callback]
   (let [file-name (get-environment-substituted-path
                    (:resource-resolver-path resource-address))]
     (if-not file-name
       (js/console.warn (str "NO SUCH FILE: " file-name))
       (let [maybe-page-number-bound
             (ci/get-maybe-page-number-bound resource-address)]
         (let [rel-uri (str file-name)]
           (pdfjslib-load-text
            rel-uri
            (:beg maybe-page-number-bound)
            (:end maybe-page-number-bound)
            callback)))))))

(ext/register-loader!
 "epub"
 (fn [resource-address callback]
   (let [file-name (get-environment-substituted-path
                    (:resource-resolver-path resource-address))]
     (if-not file-name
       (js/console.warn (str "NO SUCH FILE: " file-name))
       (let [maybe-page-number-bound
             (ci/get-maybe-page-number-bound resource-address)]
         (epubi/load-and-get-text
          file-name
          (:beg maybe-page-number-bound)
          (:end maybe-page-number-bound)
          nil
          (fn [chapters]
            (->> chapters
                 (map :text)
                 (interpose " ")
                 (apply str)
                 (callback)))))))))

(defn render
  "compatibility function for calling from nodejs; wraps all fns in
  postprocessor-coll to ensure resultant object is native #js type
  "
  [candidate-seq-loader content-loader source-text & postprocessor-coll]

  (->> postprocessor-coll
       (map (fn [postprocessor-fn]
              (fn [content xcl-spec depth]
                (postprocessor-fn content (clj->js xcl-spec) depth))))
       (apply render-transclusion
              candidate-seq-loader content-loader source-text)))

(defn get-exact-text-from-partial-string-match
  [file-path match-string on-complete]
  (let [search-tokens (clojure.string/split match-string #"\s+")
        result (atom nil)
        expanded-path (get-environment-substituted-path file-path)]
    (case (get-file-extension file-path)
      "epub" (epubi/load-and-get-text
              expanded-path
              nil
              nil
              (fn on-get-section [{:keys [section chapter text]}]
                (let [maybe-matches (sc/find-most-compact-token-matches-in-content
                                     text search-tokens)]
                  (when (= (count maybe-matches)
                           (count search-tokens))
                    (let [first-match (first maybe-matches)
                          last-match (last maybe-matches)
                          index-beg (:index first-match)
                          index-end (+ (:index last-match)
                                       (:length last-match))]
                      (swap! result assoc
                             :section section
                             :chapter chapter
                             :excerpt (subs text index-beg index-end))))))
              (fn on-complete-all-sections [_sections]
                (on-complete (clj->js @result))))

      "pdf" (pdfi/process-pdf
             expanded-path
             (fn on-get-page-text [page-num page-text]
               (let [maybe-matches (sc/find-most-compact-token-matches-in-content
                                    page-text search-tokens)]
                 (when (= (count maybe-matches)
                          (count search-tokens))
                   (let [first-match (first maybe-matches)
                         last-match (last maybe-matches)
                         index-beg (:index first-match)
                         index-end (+ (:index last-match)
                                      (:length last-match))]
                     (swap! result assoc
                            :page page-num
                            :excerpt (subs page-text index-beg index-end))))))
             (fn on-complete-page-texts [page-texts]
               (on-complete (clj->js @result))))

      nil)))

(defn parse-link [link-text]
  (-> link-text
      (sc/parse-link)
      (clj->js)))

(def yaml-loader
  (fn [resource-address callback]
    (let [file-name (get-environment-substituted-path
                     (:resource-resolver-path resource-address))]
      (if-not file-name
        (js/console.warn (str "NO SUCH FILE: " file-name))
        (let [source (.readFileSync fs file-name "utf-8")
              parsed (.safeLoad yaml source)]
          (callback parsed))))))

;;;;;;;;;;
;; yaml ;;
;;;;;;;;;;
(ext/register-loader! "yml" yaml-loader)
(ext/register-loader! "yaml" yaml-loader)

;;;;;;;;;;
;; json ;;
;;;;;;;;;;
(ext/register-loader!
 "json"
 (fn [resource-address callback]
   (let [file-name (get-environment-substituted-path
                    (:resource-resolver-path resource-address))]
     (if-not file-name
       (js/console.warn (str "NO SUCH FILE: " file-name))
       (let [source (.readFileSync fs file-name "utf-8")
             parsed (.parse js/JSON source)]
         (callback parsed))))))

;;;;;;;;;;;;;;;;;;;;;;;
;; jsonpath resolver ;;
;;;;;;;;;;;;;;;;;;;;;;;
(swap! ci/$resolver
       assoc :jsonpath
       ;; NOTE: relying on yaml to parse json AND yaml
       (fn [bound-spec content]
         (let [jsonpath (get-in bound-spec
                                [:bound :jsonpath])]
           (some-> content
                   (ext/read-yaml)
                   (ext/read-jsonpath-content jsonpath)
                   (first)))))

;;;;;;;;;;;
;; jsonl ;;
;;;;;;;;;;;
(ext/register-loader!
 "jsonl"
 (fn [resource-address callback]
   (let [file-name (get-environment-substituted-path
                    (:resource-resolver-path resource-address))]
     (if-not file-name
       (js/console.warn (str "NO SUCH FILE: " file-name))
       (-> (ci/resolve-content resource-address (slurp-file file-name))
           (callback))))))

;;;;;;;;;;;;;;;;;;;;;
;; calibre, zotero ;;
;;;;;;;;;;;;;;;;;;;;;
(swap! sc/$known-protocols
       conj :calibre :zotero)

;; TODO: need to clarify this; may be redundant
(swap! sc/$known-resource-resolver-mapping
       assoc
       :calibre
       :calibre-file)

;; TODO: need to clarify this; may be redundant
(swap! sc/$known-resource-resolver-mapping
       assoc
       :zotero
       :zotero-file)


;; originally taken from test.cljs, but test uses an internal path resolver
(defn load-local-resource [spec on-loaded]
  (cond (= :git (:resource-resolver-method spec))
        (let [gra (git/parse-git-protocol-blob-path
                   ;; this re-adds the git: protocol prefix to the parsed path;
                   ;; :link contains the git: prefixed string but also contains
                   ;; the resolver string, which parse-git-protocol-blob-path
                   ;; does not handle. consider streamlining this logic.
                   (str "git:" (:resource-resolver-path spec)))]

          (git/resolve-git-resource-address
           gra
           (fn [git-content]
             (-> (ci/resolve-content spec git-content)
                 (on-loaded)))))

        :else ;; assume filesystem based loader
        (-> spec
            (:resource-resolver-path)
            ;; NOTE this line is different from test.cljs
            (slurp-file)
            (on-loaded))))

(def $lookup-cache (atom {}))
(when @sqldb/$sqlite-db
  (sqldb/hydrate-atom-from-db! $lookup-cache @sqldb/$sqlite-db))

(defn get-text [directive callback]
  (let [protocol "xcl"
        spec (sc/parse-link directive)
        loader (or (@ext/$ExternalLoaders (-> (:resource-resolver-path spec)
                                              (clojure.string/split ".")
                                              (last)))
                   load-local-resource)]
    (if-let [cached-result (@$lookup-cache directive)]
      (do
        (callback cached-result))
      (do
        (loader spec
                (fn [full-content]
                  (let [out (ci/resolve-content spec full-content)
                        js-content (clj->js out)]
                    (swap! $lookup-cache assoc directive js-content)
                    (sqldb/save-kv!
                     directive
                     (-> js-content
                         (js/JSON.stringify)))
                    (callback out))))))))

;;;;;;;;;;
;; xsv  ;;  but really, only csv and tsv
;;;;;;;;;;
(doseq [extension ["csv" "tsv"]]
  (ext/register-loader!
   extension
   (fn [resource-address callback]
     (let [file-name (get-environment-substituted-path
                      (:resource-resolver-path resource-address))
           delimiter (xsv/get-delimiter-from-file-extension file-name)]
       (if-not file-name
         (js/console.warn (str "NO SUCH FILE: " file-name))
         (-> (ci/resolve-content resource-address (slurp-file file-name))
             (callback)))))))

(comment
  ;; example use
  (let [base-directive
        (str "git:"
             (path-join (js/process.cwd) ".git" "blob"
                        ;; git rev-parse HEAD
                        "60a987249be851cb9b0856a054261cf4d5c3244c/README.org"))]
    (get-text (str base-directive "::1-10")
              (fn [text]
                (js/console.log "=== lines 1-10 ===" text)))

    ;; note there are multiple matches for "for inline transclusion", so it needs further qualification
    (get-text (str base-directive "::for inline transclusion, since...shortest syntax")
              (fn [text]
                (js/console.log "=== segment match ===" text)))))
