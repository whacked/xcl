(ns xcl.git-interop
  (:require [xcl.common :refer [get-file-extension]]
            [xcl.core :as sc]
            [xcl.content-interop :as ci]
            ["fs" :as fs]
            ["path" :as path]
            ["js-yaml" :as yaml]
            ["jsonpath-plus" :as JSONPath]
            ["isomorphic-git" :as git]
            [xcl.node-common :refer
             [path-exists? path-join
              get-environment-substituted-path]]))

(let [git-plugin (aget git "plugins")]
  (js-invoke git-plugin "set""fs" fs))

(def $base-git-param {:fs fs})

(defn buffer-to-string [buffer]
  (.toString buffer))

(defn git-content-object-to-string [content-object]
  (-> content-object
      (aget "object")
      (buffer-to-string)))

(defn make-git-resource-address [repo-name oid-hash path-in-repo & [resolver-string]]
  ;; github's example
  ;; https://github.com/hubotio/hubot/blob/ed25584f5ac2520a6c28547ffd0961c7abd7ea49/README.md
  (comment
    (make-git-resource-address "hubot" "ed25584f5ac2520a6c28547ffd0961c7abd7ea49" "README.md"))

  (str
   repo-name "/blob"
   "/" oid-hash
   "/" path-in-repo
   (when resolver-string
     (str "::" resolver-string))))

(defrecord GitResourceAddress
    [repo-name oid path content-resolvers])

;; subsumed completely by parse-git-protocol-blob-path?
(defn parse-git-resource-address [git-resource-address]
  (comment
    (parse-git-resource-address "./blob/eb64c0e82c7c/README.org::*also see"))
  (when-let [[full-match
              repo-name
              oid-hash
              path-in-repo
              content-resolvers]
             (re-find #"([^/]+)/blob/([a-fA-F0-9]+)/([^:?]+)(.*)"
                      git-resource-address)]
    (GitResourceAddress.
     repo-name oid-hash path-in-repo content-resolvers)))

(defn parse-git-protocol-blob-path [git-protocol-path]
  (comment
    (parse-git-protocol-blob-path
     "git:../../../.git/blob/e12ac2/xcl/README.org::*content resolvers"))
  (let [known-address-pattern (str "git:(.+?)/blob/([0-9a-fA-F]{5,})/(.+)")
        [full-match
         repo-path
         commit-oid
         path-in-repo-with-resolver]
        (re-matches
         (re-pattern known-address-pattern)
         git-protocol-path)]
    (when-not full-match
      (throw (js/Error.
              (str
               "ERROR:\n"
               "  git resolver does not recognize this path notation:\n"
               git-protocol-path "\n"
               "  currently, only this pattern is understood:\n"
               known-address-pattern
               "\n"))))
    ;; WARN this only supports the org-style resolver separator and "?" tokens now
    ;;       the hard-codedness will become brittle with more flexibile syntaxes
    (let [link (sc/parse-link full-match)
          path-in-repo (-> path-in-repo-with-resolver
                           (clojure.string/split #"(::|\?)")
                           (first))
          cleaned-repo-path (-> (if (clojure.string/ends-with? repo-path "/.git")
                                  (subs repo-path 0 (- (count repo-path) 5))
                                  repo-path)
                                (get-environment-substituted-path))]
      (GitResourceAddress.
       cleaned-repo-path
       commit-oid
       (or path-in-repo
           path-in-repo-with-resolver)
       (:content-resolvers link)))))

(defn load-repo-file-from-commit [repo-dir path-in-repo commit-oid fn-on-success]
  (-> (.readObject git
                   (clj->js (assoc $base-git-param
                                   :dir (get-environment-substituted-path repo-dir)
                                   :oid commit-oid
                                   :filepath path-in-repo)))
      (.then (fn [retrieved-object]
               (-> (git-content-object-to-string retrieved-object)
                   (fn-on-success)))
             (fn [error]
               (js/console.error
                (str "git readObject failed:\n"
                     "repo-dir: " repo-dir "\n"
                     "path-in-repo: " path-in-repo "\n"
                     "commit-oid: " commit-oid "\n"))
               (js/console.error error)))))

;; WARN: GitResourceAddress overlaps its :content-resolvers with the
;;       general xcl link structure; this is why we can send it to
;;       ci/resolve-content, but this should be more cleanly and
;;       strictly combined with the more general xcl struct
(defn resolve-git-resource-address [{commit-oid :oid
                                     repo-dir :repo-name
                                     path-in-repo :path
                                     resolvers :content-resolvers
                                     :as GRA}
                                    on-resolved
                                    & [on-failed]]
  {:pre [(instance? GitResourceAddress GRA)]}

  (let [env-resolved-dir (get-environment-substituted-path
                          repo-dir)]

    (-> (.log git (clj->js (assoc $base-git-param
                                  :dir env-resolved-dir)))
        (.catch (fn [error]
                  (throw error)))
        (.then (fn [commits]
                 (loop [remain-commits
                        (->> commits
                             (array-seq)
                             (sort-by (fn [commit]
                                        (aget commit "author" "timestamp"))))]
                   (if (empty? remain-commits)
                     (do
                       (js/console.warn "failed to resolve:"
                                        (pr-str GRA))
                       (when on-failed
                         (on-failed GRA)))
                     (let [commit-description (first remain-commits)
                           commit-clj-object (js->clj commit-description :keywordize-keys true)
                           {timestamp :timestamp
                            tzoffset :timezoneOffset} (-> commit-clj-object (:author))]
                       (if-not (clojure.string/starts-with?
                                (:oid commit-clj-object)
                                commit-oid)
                         (recur (rest remain-commits))
                         (load-repo-file-from-commit
                          env-resolved-dir
                          path-in-repo
                          (:oid commit-clj-object)
                          (fn [content]
                            (-> (ci/resolve-content GRA content)
                                (on-resolved)))))))))))))
