(ns xcl.test
  (:require ["fs" :as fs]
            [xcl.core :as sc]
            [xcl.content-interop :as ci]
            [xcl.node-interop :as ni]
            [xcl.node-common :refer
             [path-join]]
            [xcl.external :as ext]
            [xcl.calibre-interop :as calibre]
            [xcl.zotero-interop :as zotero]
            [xcl.git-interop :as git]
            [xcl.console
             :refer [log-red log-green log-yellow
                     log-blue log-magenta log-cyan]]))

(def all-tests (atom []))

(defn slurp [path]
  (.readFileSync fs path "utf-8"))

(def $XCL-SERVER-RESOURCE-BASE-DIR
  (path-join
   (.cwd js/process)))

(defn get-local-resource-path
  [file-name]
  (if (clojure.string/starts-with? file-name "/")
    file-name
    (path-join
     $XCL-SERVER-RESOURCE-BASE-DIR
     file-name)))

(defn load-local-resource [spec on-loaded]
  (cond (= :git (:resource-resolver-method spec))
        (let [gra (git/parse-git-protocol-blob-path (:link spec))]
          (git/resolve-git-resource-address
           gra
           (fn [git-content]
             (-> (ci/resolve-content spec git-content)
                 (on-loaded)))))

        :else ;; assume filesystem based loader
        (-> spec
            (:resource-resolver-path)
            (get-local-resource-path)
            (slurp)
            (on-loaded))))

(defn run-all-tests! []
  (when-let [test-func (first @all-tests)]
    (swap! all-tests rest)
    (test-func)))

(defn signal-test-done! []
  (log-magenta
   (str (count @all-tests) " tests remaining..."))
  (if (<= (count @all-tests) 0)
    (js/process.exit)
    (run-all-tests!)))

(defn fs-abspath-and-relpath-file-loader []
  (let [abspath-spec (sc/parse-link
                      ;; this works under the assumption that the test process is being invoked via
                      ;; `node build/test.js`; at run-time, it still resolves to starting from the
                      ;; root directory of the `transclusion-minor-mode` repo
                      "file:src/../README.org::*example usage")
        resolved-abspath-content
        (->> (:resource-resolver-path abspath-spec)
             (get-local-resource-path)
             (slurp)
             (ci/resolve-content abspath-spec))

        relpath-spec (sc/parse-link
                      (str "file:"
                           (js/process.cwd)
                           "/README.org::*example usage"))

        resolved-relpath-content
        (->> (:resource-resolver-path relpath-spec)
             (get-local-resource-path)
             (slurp)
             (ci/resolve-content relpath-spec))]

    (if (and (< 0 (count resolved-abspath-content))
             (clojure.string/starts-with? resolved-abspath-content
                                          "* example usage")
             (= resolved-abspath-content resolved-relpath-content))
      (log-green "[FS] abspath / relpath file loader test OK")
      (log-red (str "[FS] abspath / relpath test FAIL:\n"
                "- ABSPATH:\n"
                resolved-abspath-content "\n"
                "- RELPATH:\n"
                resolved-relpath-content "\n")))

    (signal-test-done!)))

(defn git-direct-content-loader-test []
  (let [path-in-repo "src/calibre.sql"
        commit-oid "a5bee82595269ad8d14fc4fdc5e80d62cf71e421"
        expected-content (-> $XCL-SERVER-RESOURCE-BASE-DIR
                             (path-join path-in-repo)
                             (slurp))]
    (git/load-repo-file-from-commit
     $XCL-SERVER-RESOURCE-BASE-DIR
     path-in-repo
     commit-oid
     (fn [text]
       (if (= expected-content text)
         (do
           (log-green "[GIT] load content OK"))
         (do
           (log-red "[GIT] load content fail")
           (println "=== EXPECTED ===" expected-content)
           (println "=== RECEIVED ===" text)))
       (signal-test-done!)))))

(defn git-resolved-content-loader-test-org-style []
  (let [git-href
        (str "git:" $XCL-SERVER-RESOURCE-BASE-DIR
             "/blob/3fa84e47e7f3c9fa38de1c531586a2b213e63aeb/README.org::*example usage")
        spec (sc/parse-link git-href)]
    (load-local-resource
     spec
     (fn [full-content]
       (let [resolved-content (ci/resolve-content spec full-content)]
         (if (clojure.string/starts-with?
              resolved-content
              "* example usage")
           (log-green "[GIT] org-style resolved content loader OK")
           (do
             (log-red "[GIT] org-style resolved content loader FAIL")
             (println "=== RECEIVED  ===" resolved-content)))
         (signal-test-done!))))))

(defn git-resolved-content-loader-test-jsonl-jq-query-style []
  (let [git-href
        (str "git:" $XCL-SERVER-RESOURCE-BASE-DIR
             "/blob/ec2b3941a27a627cffc09af5df66edfe94720202/public/test-dataset.jsonl?jq=.[4].Team")
        spec (sc/parse-link git-href)]
    (load-local-resource
     spec
     (fn [resolved-content]
       (if (= resolved-content "Red")
         (log-green "[GIT] jsonl + jq-style resolved content loader OK")
         (do
           (log-red (str "[GIT] jsonl + jq-style resolved content loader FAIL (received: " resolved-content ")"))
           (println "=== RECEIVED  ===" resolved-content)))
       (signal-test-done!)))))

(defn git-resolved-content-loader-test-tsv-jq-query-style []
  (let [git-href
        (str "git:" $XCL-SERVER-RESOURCE-BASE-DIR
             "/blob/ec2b3941a27a627cffc09af5df66edfe94720202/public/test-dataset.tsv?jq=.[4].Team")
        spec (sc/parse-link git-href)]
    (load-local-resource
     spec
     (fn [resolved-content]
       (if (= resolved-content "Red")
         (log-green "[GIT] tsv + jq-style resolved content loader OK")
         (do
           (log-red (str "[GIT] tsv + jq-style resolved content loader FAIL (received: " resolved-content ")"))
           (println "=== RECEIVED  ===" resolved-content)))
       (signal-test-done!)))))

(defn zotero-test-pdf []
  (zotero/load-text-from-file
   "Trace-based just-in-time*.pdf"
   "Monkey observes that so TraceMonkey attempts"
   (fn [page text]
     (log-green "[zotero test pdf OK]")
     (js/console.info
      (str "    page: " page "\n"
           "    "
           text
           "\n\n"))
     (signal-test-done!))
   (fn [err]
     (js/console.error err)
     (signal-test-done!))))

(defn zotero-test-html []
  ;; from example.com
  (zotero/load-text-from-file
   "*Example Domain*.html"
   "examples without prior coordination or asking"
   (fn [text]
     (log-green "[zotero test html OK]")
     (js/console.info
      (str "    "
           text
           "\n\n"))
     (signal-test-done!))
   (fn [err]
     (js/console.error err)
     (signal-test-done!))))

(defn calibre-test []
  (calibre/load-text-from-epub
   "QuIcK sTaRt*.epub"
   "Calibre display possible matches for entered"
   (fn [text]
     (log-green "[calibre OK]")
     (js/console.log
      (str "    calibre epub: " (count text) " bytes\n\n"))
     (signal-test-done!))))

(defn pdf-loader-test []
  (let [external-loader (@ext/$ExternalLoaders "pdf")
        resource-spec (sc/parse-link
                       "xcl:./public/tracemonkey.pdf?p=3&s=Monkey observes that...so TraceMonkey attempts")]
    (external-loader
     resource-spec
     (fn [text]
       (log-green "[PDF OK]")
       (js/console.log (str "    " (pr-str resource-spec) "\n"
                            "    " (-> text
                                       (clojure.string/trim)
                                       (subs 0 200))
                            "\n\n"))
       (signal-test-done!)))))

(defn epub-loader-test []
  (let [external-loader (@ext/$ExternalLoaders "epub")
        resource-spec (sc/parse-link
                       "xcl:./public/alice.epub?p=2&s=Would you tell me, please...walk long enough")]
    (external-loader
     resource-spec
     (fn [text]
       (log-green "[EPUB OK]")
       (js/console.log (str "    " (pr-str resource-spec) "\n"
                            "    " (-> text
                                       (clojure.string/trim)
                                       (subs 0 200))
                            "\n\n"))
       (signal-test-done!)))))

(defn csv-loader-test []
  (let [external-loader (@ext/$ExternalLoaders "csv")
        resource-spec (sc/parse-link "xcl:./public/test-dataset.csv?A1=B4")]
    (external-loader
     resource-spec
     (fn [text]
       (if (= text "Diana")
         (log-green "[CSV OK]")
         (log-red (str "[CSV BAD] received: " text)))
       (js/console.log (str "    " (pr-str resource-spec) "\n"
                            "    " text
                            "\n\n"))
       (signal-test-done!)))))

(defn tsv-loader-test []
  ;; same as csv, but use the jq loader
  (let [external-loader (@ext/$ExternalLoaders "csv")
        resource-spec (sc/parse-link "xcl:./public/test-dataset.tsv?jq=.[2].Name")]
    (external-loader
     resource-spec
     (fn [text]
       (if (= text "Charlie")
         (log-green "[TSV OK]")
         (log-red "[TSV BAD"))
       (signal-test-done!)))))

(defn jsonl-loader-test []
  (let [external-loader (@ext/$ExternalLoaders "jsonl")
        resource-spec (sc/parse-link "xcl:./public/test-dataset.jsonl?jq=.[3].Name")]
    (external-loader
     resource-spec
     (fn [text]
       (if (= text "Diana")
         (log-green "[JSONL OK]")
         (log-red "[JSONL BAD]"))
       (signal-test-done!)))))

(defn add-node-test! [test-func]
  (log-cyan ">>>>>>>>> adding a test!")
  (swap! all-tests conj test-func)
  (log-blue (str " ----- added a test; now " (count @all-tests))))

(defn -main []
  (js/console.log
   (str "### script running from dir\n"
        "    " js/__dirname "\n"
        "### process running from dir\n"
        "    " (.cwd js/process)
        "\n\n"))

  (defn load-from-directive [directive]
    (let [spec (sc/parse-link directive)
          full-content (slurp
                        (get-local-resource-path
                         (:resource-resolver-path spec)))]
      (log-yellow
       (str "*** SPEC ***\n"
            (:resource-resolver-path spec) "\n"
            (pr-str spec)))
      (log-green
       (str "\n"
            "========================================"))
      (println (ci/resolve-content
                spec full-content))
      (log-green "----------------------------------------")))

  (add-node-test!
   (fn default-file []
     (let [directive "public/transcluding-org-elements.org"]
       (load-from-directive directive)
       (signal-test-done!))))

  (add-node-test!
   (fn fs-file []
     (let [directive "LICENSE::2-10"]
       (load-from-directive directive)
       (signal-test-done!))))

  (add-node-test!
   (fn fs-file-with-line-range []
     (let [directive "file:public/transcluding-org-elements.org::153,185"]
       (load-from-directive directive)
       (signal-test-done!))))

  (add-node-test!
   (fn fs-file-with-org-heading []
     (let [directive "file:README.org::*line range"]
       (load-from-directive directive)
       (signal-test-done!))))

  (add-node-test!
   (fn fs-file-with-token-bound []
     (let [directive "file:README.org::There is 1 goal...a range resource"]
       (load-from-directive directive)
       (signal-test-done!))))

  (add-node-test!
   (fn xcl-first-token-range []
     (let [directive "xcl:README.org?s=There is 1 goal...a range resource"]
       (load-from-directive directive)
       (signal-test-done!))))

  (add-node-test!
   (fn xcl-first-matching-paragraph []
     (let [directive "xcl:README.org?para=the+pipe+is+an+obvious"]
       (load-from-directive directive)
       (signal-test-done!))))

  (add-node-test!
   (fn xcl-yaml-jsonpath []
     (let [directive "xcl:public/test-highlight-file.yml?jsonpath=$.highlights[2].highlightText"]
       (load-from-directive directive)
       (signal-test-done!))))

  (when zotero/$zotero-library-directory
    (add-node-test! zotero-test-pdf)
    (add-node-test! zotero-test-html))

  (when calibre/$calibre-library-directory
    (add-node-test! calibre-test))

  (add-node-test! pdf-loader-test)

  (add-node-test! epub-loader-test)

  (add-node-test! csv-loader-test)

  (add-node-test! tsv-loader-test)

  (add-node-test! jsonl-loader-test)

  (add-node-test! git-direct-content-loader-test)

  (add-node-test! git-resolved-content-loader-test-org-style)

  (add-node-test! git-resolved-content-loader-test-jsonl-jq-query-style)

  (add-node-test! git-resolved-content-loader-test-tsv-jq-query-style)

  (add-node-test! fs-abspath-and-relpath-file-loader)

  (run-all-tests!))

(-main)
