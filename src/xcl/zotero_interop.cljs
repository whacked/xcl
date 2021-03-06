(ns xcl.zotero-interop
  (:require ["sqlite3" :as sqlite3]
            ["fs" :as fs]
            ["ini" :as ini]
            [xcl.common :refer
             [get-file-extension]]
            [xcl.node-common :refer
             [path-exists? path-join]]
            [xcl.core :as sc]
            [xcl.content-interop :as ci]
            [xcl.sqlite :as sqlite]
            [xcl.external :as ext]
            [xcl.dom-processing :as domp]
            [xcl.pdfjslib-interop :as pdfjslib]))

(defn detect-zotoro-profile-base-directory []
  (let [process-user (aget js/process "env" "USER")]
    (loop [remain
           [(aget js/process "env" "USERPROFILE") ;; windows
            (aget js/process "env" "HOME")
            (path-join "/home" process-user)
            (path-join "/Users" process-user)]
           maybe-zotero-dir nil]
      (let [found-path? (some-> maybe-zotero-dir
                                (path-exists?))]
        (if (or (empty? remain)
                found-path?)
          (when found-path? maybe-zotero-dir)
          (recur
           (rest remain)
           (when-let [candidate (first remain)]
             (path-join candidate ".zotero/zotero"))))))))

(def $zotero-library-directory
  (when-let [zotero-profile-base-directory
             (detect-zotoro-profile-base-directory)]
    (let [profile-ini-js (->> (.readFileSync
                               fs
                               (path-join
                                zotero-profile-base-directory
                                "profiles.ini")
                               "utf-8")
                              (.parse ini))
          profile-config (js->clj profile-ini-js :keywordize-keys true)]
      (when-let [profile-dir
                 (get-in profile-config [:Profile0 :Path])]
        (let [profile-dir-fullpath
              (path-join zotero-profile-base-directory
                         profile-dir)]
          (when (path-exists? profile-dir-fullpath)
            ;; note that if the user creates an override in user.js,
            ;; it should get read by Zotero on open, and written back
            ;; into prefs.js, so we will use that as ground truth
            (let [maybe-prefs-js-path
                  (path-join profile-dir-fullpath "prefs.js")]
              (when (path-exists? maybe-prefs-js-path)
                (some->> (.readFileSync fs maybe-prefs-js-path "utf-8")
                         (clojure.string/split-lines)
                         (remove empty?)
                         (filter (fn [line]
                                   (re-find #"extensions.zotero.dataDir" line)))
                         (map (fn [line]
                                (some-> line
                                        (clojure.string/split #",")
                                        (last)
                                        (clojure.string/split #"\"")
                                        (second))))
                         (first))))))))))

(def $zotero-db-path
  (some-> $zotero-library-directory
          (path-join "zotero.sqlite")))

(if-not $zotero-library-directory
  (js/console.error "ERROR: could not detect zotero library path")
  (js/console.info (str "REGISTERING LOADER FOR zotero; storage detected at " $zotero-db-path)))

(def $zotero-db
  (when $zotero-db-path
    (try
      (new sqlite3/Database
           $zotero-db-path
           sqlite3/OPEN_READONLY)
      (catch js/Error e
        (js/console.error
         (str "ERROR: could not open zotero database at "
              $zotero-db-path
              " / " e))))))

(defn zotero-get-attachment-key-path-filepath
  [attachment-key attachment-path]
  (when (and attachment-key attachment-path)
    (path-join
     $zotero-library-directory
     "storage"
     attachment-key
     (clojure.string/replace attachment-path #"^storage:" ""))))

(defn find-matching-file
  [file-search-string
   on-found-file
   & [on-not-exist]]
  (let [zotero-query (sqlite/filename-glob-to-query file-search-string)]
    (-> $zotero-db
        (.all
         (aget sqlite/$sql "zoteroQueryByAttributes")
         zotero-query
         1 ;; limit
         (fn [err js-rows]
           (when err (js/console.error err))
           (when-let [rows (js->clj js-rows :keywordize-keys true)]
             (let [{:keys [attachmentKey
                           attachmentPath]} (first rows)
                   file-path (zotero-get-attachment-key-path-filepath
                              attachmentKey attachmentPath)]

               (if (path-exists? file-path)
                 (on-found-file file-path)
                 (if on-not-exist
                   (on-not-exist file-path)
                   (js/console.error
                    (str "FILE AT [" file-path "] DOES NOT EXIST")))))))))))

(defn load-text-from-html
  [file-path
   target-string-or-spec
   on-found-text
   & [on-error]]
  (let [xhtml-string (.readFileSync fs file-path "utf-8")
        text-content (-> xhtml-string
                         (domp/xhtml-string->text))]
    (if (string? target-string-or-spec)
      (let [tokens (clojure.string/split
                    target-string-or-spec #"\s+")]
        (on-found-text
         (sc/get-resolved-substring-from-tokens
          text-content tokens)))
      (when-let [resolved-text (ci/resolve-content
                                target-string-or-spec
                                text-content)]
        (js/console.info
         (str "[zotero RANGE LOAD]\n"
              (pr-str target-string-or-spec)))
        (on-found-text resolved-text)))))

(defn load-text-from-pdf
  [file-path
   target-string-or-spec
   on-found-text
   & [on-error]]
  (pdfjslib/process-pdf
   file-path
   (fn on-get-page-text [page-number text]
     {:page page-number
      :text text})
   (fn on-complete-page-texts [pagetexts]
     (let [_page-number-with-string
           (some->> pagetexts
                    (filter
                     (fn [{:keys [page text]}]
                       (if (string? target-string-or-spec)
                         ;; find tokens directly
                         (let [tokens (clojure.string/split
                                       target-string-or-spec #"\s+")]
                           (on-found-text
                            (sc/get-resolved-substring-from-tokens
                             text tokens)
                            page))

                         ;; use specification based resolution
                         (when-let [resolved-text (ci/resolve-content
                                                   target-string-or-spec
                                                   text)]
                           (js/console.info
                            (str "[zotero RANGE LOAD]\n"
                                 "    page: " page "\n"
                                 "    "
                                 (pr-str target-string-or-spec)))
                           (on-found-text resolved-text page)))))
                    (first)
                    (:page))]
       _page-number-with-string))
   (or on-error identity)))

(defn load-text-from-file
  [file-search-string
   target-string-or-spec
   on-found-text
   & [on-error]]
  (find-matching-file
   file-search-string
   (fn [file-path]
     (let [extension (get-file-extension file-path)
           handler (case extension
                     "pdf" load-text-from-pdf
                     "html" load-text-from-html
                     nil)]
       (if handler
         (find-matching-file
          file-search-string
          (fn [file-path]
            (handler
             file-path target-string-or-spec
             on-found-text on-error)))
         (do
           (js/console.warn
            (str "[zotero WARNING] unhandled extension: " extension))
           (on-found-text file-path)))))))
