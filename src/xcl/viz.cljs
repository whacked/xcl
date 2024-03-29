(ns xcl.viz
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [goog.dom :as gdom]
            [xcl.core :as sc]
            [xcl.corpus :as corpus]
            [xcl.common :refer [get-file-extension]]
            [xcl.content-interop :as ci]
            [xcl.external-js-content :as ext-js]
            [xcl.external :as ext]
            [ajax.core :refer [ajax-request]]
            [xcl.env :as env]

            [xcl.common :refer [re-pos conj-if-non-nil
                                get-file-extension]]
            ))
(def $JSONRPC-SERVER-ENDPOINT
  (str "//" (env/get :jsonrpc-host) ":" (env/get :jsonrpc-port) (env/get :jsonrpc-endpoint) "/"))

(swap! sc/$known-protocols
       conj :fakeout)

(swap! sc/$known-protocols
       conj :calibre :zotero)

(defn get-static-content
  [search-path]
  (corpus/file-cache search-path))


;; <json, yaml loaders>
(defn make-json-like-handler
  [deserializer]
  (fn [resource-address callback]
    (let [file-name (:resource-resolver-path resource-address)]
      (if-not file-name
        (js/alert (str "NO SUCH FILE: " file-name))
        (if-let [maybe-jsonpath-bound
                 (:jsonpath
                  (ci/get-maybe-jsonpath-bound resource-address))]
          (some-> (get-static-content file-name)
                  (deserializer)
                  (ext/read-jsonpath-content
                   maybe-jsonpath-bound)
                  (first)
                  (callback))
          (callback (get-static-content file-name)))))))

(ext/register-loader!
 "json"
 (make-json-like-handler ext/read-json))

(def yaml-loader
  (make-json-like-handler ext/read-yaml))

(ext/register-loader! "yml" yaml-loader)
(ext/register-loader! "yaml" yaml-loader)
;; </json, yaml loaders>


(defn render-map [m]
  {:pre [(map? m)]}
  (->> m
       (sort)
       (map-indexed
        (fn [i [k v]]
          ^{:key (str i "-" k)}
          [:tr
           [:th [:code k]]
           [:td [:code (subs
                        (pr-str v)
                        0 500)]]]))
       (concat [:tbody])
       (vec)
       (vector :table {:style {:font-size "x-small"}})
       (vec)))

;; TODO: revisit this special case ugliness.
;;       currently, this declaration is required to make the
;;       browser request the file content from the server
(def -external-file-types
  #{"pdf" "epub" "csv" "tsv" "jsonl"})

(defn resolve-resource-spec-async
  [link on-resolved-resource-spec]

  (cond (clojure.string/starts-with? link "<<org-text>>")
        (->> (@corpus/org-text-buffer "<<org-text>>")
             (assoc (sc/parse-link link) :org-text)
             (on-resolved-resource-spec))

        :else
        (sc/get-resource-match-async
         ;; candidate-seq-loader-async
         (fn [resource-name-matcher
              callback]

           (cond (and resource-name-matcher
                      (-external-file-types
                       (get-file-extension resource-name-matcher)))
                 (callback [resource-name-matcher])

                 ;; file in corpus
                 :else
                 (do
                   (js/console.warn "file is in corpus: " link)
                   (->> (corpus/list-files
                         resource-name-matcher)
                        (callback)))))

         ;; content-loader-async
         (fn [resolved-spec callback]
           (js/console.log "hitting content loader for\n"
                           (pr-str
                            (select-keys resolved-spec
                                         [:resource-resolver-method
                                          :resource-resolver-path])))
           (let [path (:resource-resolver-path resolved-spec)]
             (when-let [content (corpus/file-cache path)]
               (callback content))))

         ;; link
         link

         ;; callback
         on-resolved-resource-spec)))

(defn load-content-async
  [resource-spec on-content]
  (let [path (:resource-resolver-path resource-spec)
        extension (when path
                    (get-file-extension path))]

    (cond (@ext/$ExternalLoaders extension)
          (let [external-loader (@ext/$ExternalLoaders extension)]
            (external-loader
             resource-spec on-content))

          (:org-text resource-spec)
          (let [org-content (:org-text resource-spec)]
            (on-content org-content))

          :else
          (do
            (js/console.warn
             (str "using fallback loader (corpus cache exact match):\n"
                  (pr-str resource-spec)))
            (js/setTimeout
             (fn []
               (on-content
                (corpus/file-cache path)))
             (* 1000 (Math/random)))))))

(defn render-highlights-in-text [text highlights]
  (loop [remain (reverse highlights)
         current-index (count text)
         out []]
    (if (empty? remain)
      (reverse
       (if (= 0 current-index)
             out
             (conj out
                   ^{:key (str "highlight-" current-index)}
                   [:span (subs text 0 current-index)])))
      (let [highlight (first remain)
            h-index (:index highlight)
            h-length (:length highlight)
            h-end (+ h-index h-length)]
        (recur (rest remain)
               h-index
               (conj (if (< h-end current-index)
                       (conj out
                             ^{:key (str current-index "-" h-index "-interim-" highlight)}
                             [:span (subs text h-end current-index)])
                       out)
                     ^{:key (str current-index "-" h-index "-normal-" highlight)}
                     [:span
                      {:style {:background "green"
                               :color "yellow"}}
                      (subs text h-index h-end)]))))))

(defn render-text-anchoring-test-view! [view-state]
  (let [use-compact? (r/atom true)]
    [(fn []
       [:div

        [:h2 "text anchoring"]

        [:table
         {:style {:border-collapse "collapse"
                  :font-family "Consolas, Inconsolata, Monaco, Ubuntu, Monospace"
                  :font-size "x-small"}}
         [:style "td { border: 1px solid gray; }"]
         [:tbody
          [:tr
           [:th]
           [:th "content"]
           [:th "target"]
           [:th "matches"]]

          (->> [[" one two two three three three four four four four "
                 " two three "]
                [" van car car car boat car car car boat boat truck boat van van car train "
                 " car car boat van "]]
               (map (fn [[content target]]
                      (let [tokens (-> target
                                       (clojure.string/trim)
                                       (clojure.string/split #"\s+"))
                            all-matches (->> tokens
                                             (map
                                              (fn [token]
                                                (->> (sc/find-all-match-candidate-indexes-in-content
                                                      token content)
                                                     (map (fn [index]
                                                            {:index index
                                                             :token token
                                                             :length (count token)}))))))]
                        (->> all-matches
                             (sc/get-all-valid-token-match-arrangements)
                             (map (fn [token-match-arrangement]
                                    ^{:key token-match-arrangement}
                                    [:tr
                                     [:td]
                                     [:td
                                      (render-highlights-in-text
                                       content token-match-arrangement)]
                                     [:td target]
                                     [:td
                                      (->
                                       token-match-arrangement
                                       (clj->js)
                                       (js/JSON.stringify nil 2))]]))))))
               (apply concat))

          [:tr
           [:th "method"]
           [:th "content"]
           [:th "target"]
           [:th "matches"]]

          (->> [[:default
                 " a b c d f g "
                 " b d       f"]
                [:default
                 " one two three four five  "
                 "  four six "]
                [:default
                 " van car car car boat car car car boat boat truck boat van van car train "
                 " car car boat van "]
                [:compact
                 " van car car car boat car car car boat boat truck boat van van car train "
                 " car car boat van "]]
               (map-indexed
                (fn [i [method content target]]
                  (let [match-method (case method
                                       :default sc/find-successive-tokens-in-content
                                       :compact sc/find-most-compact-token-matches-in-content)
                        matches (match-method
                                 content
                                 (-> target
                                     (clojure.string/trim)
                                     (clojure.string/split #"\s+")))]
                    ^{:key (str i "-" match-method)}
                    [:tr
                     [:td
                      (name method)]
                     [:td
                      (render-highlights-in-text content matches)]
                     [:td target]
                     [:td
                      [:pre
                       (-> matches
                           (clj->js)
                           (js/JSON.stringify nil 2))]]]))))]]

        (let [doc-names ["tiny.org"
                         "big.org"
                         "fake.org"
                         "dummy.org"
                         "xcl-test-3-a.org"
                         "100lines"]
              doc-blobs (->> doc-names
                             (map (fn [fname]
                                    (-> fname
                                        (get-static-content)
                                        (subs 0 500)))))
              targets
              ["  fourth line "
               " 5th   line "
               "  fake      file  "
               "ake"
               "  you "
               "you"
               "  in    the  "
               " in and CATS"
               " aye aye"
               "SOME LINE 30"]

              all-matches (sc/find-corpus-matches-by-tokenization
                           doc-blobs targets
                           :compact? @use-compact?)]
          [:div
           [:label
            [:input
             {:type "checkbox"
              :checked @use-compact?
              :on-change (fn [evt]
                           (reset! use-compact? (aget evt "target" "checked")))}]
            "prefer matches with maximal token cluster compactness?"]
           [:table
            {:style {:border-collapse "collapse"}}
            [:style "td { border: 1px solid gray; }"]
            [:tbody
             [:tr
              [:th "index"]
              [:th "corpus source"]
              [:th "hit strings"]
              [:th "(rendered) content"]
              [:th "hits"]]
             (->> (map vector (range (count doc-names)) doc-names doc-blobs)
                  (map (fn [[index fname content]]
                         (let [matches-for-index
                               (when-let [maybe-matches (get all-matches index)]
                                 maybe-matches)]
                           ^{:key (str index "-" fname)}
                           [:tr
                            [:td index]
                            [:td fname]
                            [:td
                             [:ul
                              (->> matches-for-index
                                   (map :target)
                                   (map-indexed
                                    (fn [i target]
                                      ^{:key (str i "-" target)}
                                      [:li target])))]
                             ]
                            [:td
                             [:pre
                              (render-highlights-in-text
                               content
                               (->> matches-for-index
                                    (map :matches)
                                    (apply concat)))]]
                            [:td
                             [:pre
                              {:style {:font-size "xx-small"}}
                              (-> (clj->js matches-for-index)
                                  (js/JSON.stringify nil 2))]]]))))]]])])]))

(defn render-resource-resolver-test-view! [view-state]
  (let [cases [["exact match"
                "LICENSE" "LICENSE"]
               ["exact match"
                "file:tiny.org" "tiny.org"]
               ["exact match"
                "file:test-note-file.json" "test-note-file.json"]
               ["exact match"
                "grep:ZZ+you" "dummy.org"]
               ["exact match"
                "file:dummy: the clone.org" "dummy: the clone.org"]
               ["glob file name"
                "file:d*y.org" "dummy.org"]
               ["glob with chained post-processors"
                "file:d*y.org|proc1|proc2" "dummy.org"]
               ["glob file name"
                "file:dummy: the clone*" "dummy: the clone.org"]
               ["fuzzy find file by content +"
                "grep:ZZ+you" "dummy.org"]
               ["fake protocol"
                "fakeout:dummy.org" "dummy.org"]]]
    (->> cases
         (map-indexed
          (fn [i [desc link expected-name]]
            (let [received-name (r/atom nil)]
              [(fn []

                 (resolve-resource-spec-async
                  link
                  (fn [resolved-resource-spec]
                    (reset! received-name
                            (:resource-resolver-path
                             resolved-resource-spec))))

                 (let [success? (if (nil? @received-name)
                                  nil
                                  (= expected-name @received-name))]
                   (if-not (and (get-in @view-state [:hide-passing?])
                                success?)
                     ^{:key (str i "-" link)}
                     [:tr
                      [:td
                       {:style (case success?
                                 true {:background-color "lime" :color "white"}
                                 false {:background-color "red" :color "white"}
                                 {})}
                       (case success?
                         true "PASS"
                         false "FAIL"
                         "")]
                      [:td desc]
                      [:td link]
                      [:td expected-name]
                      [:td @received-name]])))])))
         (concat [:tbody
                  [:tr
                   [:th "PASS?"]
                   [:th "description"]
                   [:th "link expression"]
                   [:th "expected"]
                   [:th "received"]]])
         (vec)
         (vector :table
                 {:style {:border-collapse "collapse"}}
                 [:style "td { border: 1px solid gray; }"])
         (vec)
         (vector :div
                 [:h2 "resource resolver test"]))))

(defn render-link-test-view! [view-state]
  (->> [["grab text from json"
         "xcl:test-note-file.json?jsonpath=$[6].content"
         "floating note"
         "test-note-file.json" :exact-name
         nil
         [{:type :jsonpath
           :bound {:jsonpath "$[6].content"}}]]
        ["grab text from yml"
         "xcl:test-highlight-file.yml?jsonpath=$.highlights[2].highlightText"
         "yaml indented text block that spans 2 lines"
         "test-highlight-file.yml" :exact-name
         nil
         [{:type :jsonpath
           :bound {:jsonpath "$.highlights[2].highlightText"}}]]

        ["line in file"
         "LICENSE::7"
         "of this license document, but changing it is not allowed."
         "LICENSE" :exact-name
         :line-range {:beg 7 :end 7}]
        ["line in file"
         "file:100lines::5"
         "5 SOME LINE"
         "100lines" :exact-name
         :line-range {:beg 5 :end 5}]
        ["line in file, dot relative path"
         "file:./big.org::1"
         "another fake file"
         "big.org" :exact-name
         :line-range {:beg 1 :end 1}]
        ["line in file, relative path traversal"
         "file:somewhere/../big.org::1"
         "another fake file"
         "big.org" :exact-name
         :line-range {:beg 1 :end 1}]
        ["line in file, absolute path"
         "file:/tmp/some-big.org::1"
         "fake file in temp dir"
         "/tmp/some-big.org" :exact-name
         :line-range {:beg 1 :end 1}]
        ["line range"
         "file:tiny.org::2-3"
         "* decoy 1\n* something third line"
         ;; (get-static-content "tiny.org")
         "tiny.org" :exact-name
         :line-range {:beg 2 :end 3}]
        ["line from start"
         "file:tiny.org::-2"
         "fake file (line 1)\n* decoy 1"
         ;; (get-static-content "tiny.org")
         "tiny.org" :exact-name
         :line-range {:beg nil :end 2}]
        ["line to end"
         "file:tiny.org::7-"
         "seven 7s\nocho acht"
         "tiny.org" :exact-name
         :line-range {:beg 7 :end nil}]
        ["line to end, with post processor expression"
         "file:sub/directory/xcl-in-subdir.org::2-|rewrite-relative-paths"
         "glider\n\nyou can [[file:sub/directory/more/fly-away.org]] with me\n\nI can [[sub/directory/more/fly-away.org]] with you\n\n[[fly-away]]\n\nthe end."
         "sub/directory/xcl-in-subdir.org" :exact-name
         :line-range {:beg 2 :end nil}
         {:post-processors ["rewrite-relative-paths"]}]
        ["character range"
         "tiny.org::5,40"
         "file (line 1)\n* decoy 1\n* something"
         "tiny.org" :exact-name
         :char-range {:beg 5 :end 40}]
        ["character from start"
         "file:tiny.org::,20"
         "fake file (line 1)\n*"
         "tiny.org" :exact-name
         :char-range {:beg nil :end 20}]
        ["character to end"
         "file:tiny.org::75,"
         "h line\nsix sixths is sick sith\nseven 7s\nocho acht"
         "tiny.org" :exact-name
         :char-range {:beg 75 :end nil}]
        ["percent range"
         "100lines::1%-3%"
         "2 SOME LINE\n3 SOME LINE\n4 SOME LINE"
         "100lines" :exact-name
         :percent-range {:beg 1 :end 3}]
        ["native org: heading"
         "file:tiny.org::*decoy 1"
         "* decoy 1"
         "tiny.org" :exact-name
         :org-heading {:heading "decoy 1"}]
        ["native org: heading 2 star"
         "file:dummy.org::*famous script"
         "** famous script\n\n   Captain and CATS\n   \n   In 2101, war was beginning. What happen? Main screen turn on. For great justice. Move ZIG."
         "dummy.org" :exact-name
         :org-heading {:heading "famous script"}]
        ["native org: heading with url-encoded utf-8"
         "file:dummy: the clone.org::*random%20extra%20content%20%E4%B8%96%E7%95%8C"
         "* random extra content 世界                                     :tricky:trap:\n\n  Support attributes like ~SCHEDULED:~."
         "dummy: the clone.org" :exact-name
         :org-heading {:heading "random extra content 世界"}]
        ["native org: heading with as-is utf-8"
         "file:dummy.org::*random extra%20content 世界"
         "* random extra content 世界                                     :tricky:trap:\n\n  Support attributes like ~SCHEDULED:~."
         "dummy.org" :exact-name
         :org-heading {:heading "random extra content 世界"}]
        ["native org: heading with 1 percent-encoding"
         "file:dummy.org::*percent sign %25: to mess:you:up"
         "* percent sign %: to mess:you:up\n\n  %escaping your heading link%"
         "dummy.org" :exact-name
         :org-heading {:heading "percent sign %: to mess:you:up"}]
        ["native org: heading with all percent-encoding"
         "file:dummy.org::*percent%20sign%20%25:%20to%20mess:you:up"
         "* percent sign %: to mess:you:up\n\n  %escaping your heading link%"
         "dummy.org" :exact-name
         :org-heading {:heading "percent sign %: to mess:you:up"}]
        ["exact string match range"
         "file:dummy.org::in 2101...for great justice"
         "In 2101, war was beginning. What happen? Main screen turn on. For great justice"
         "dummy.org" :exact-name
         :token-bound {:token-beg "in 2101"
                       :token-end "for great justice"}]
        ["glob file name"
         "file:d*y.org::*huh"
         "* huh\n\nwhatever is in the block"
         "d*y.org" :glob-name
         :org-heading {:heading "huh"}]
        ["fuzzy find file by content +"
         "grep:ZZ+you::*huh"
         "* huh\n\nwhatever is in the block"
         "ZZ+you" :grep-content
         :org-heading {:heading "huh"}]
        ["fuzzy find file by content raw space"
         "grep:ZZ you::*huh"
         "* huh\n\nwhatever is in the block"
         "ZZ you" :grep-content
         :org-heading {:heading "huh"}]
        ["fuzzy find file by html entity"
         "grep:ZZ%20you::*huh"
         "* huh\n\nwhatever is in the block"
         "ZZ you" :grep-content
         :org-heading {:heading "huh"}]
        ["constrict by org node ID"
         "xcl:dummy.org?id=my-node-id"
         "* next heading\n  :PROPERTIES:\n  :CUSTOM_ID: my-node-id\n  :END:\n\n  good stuff"
         "dummy.org" :exact-name
         :org-node-id {:id "my-node-id"}]
        ["constrict by first token range"
         "xcl:dummy.org?s=in 2101...for great justice."
         "In 2101, war was beginning. What happen? Main screen turn on. For great justice."
         "dummy.org" :exact-name
         :token-bound {:token-beg "in 2101"
                       :token-end "for great justice."}]
        ["constrict by nearest line"
         "xcl:dummy.org?line=support+scheduled"
         "Support attributes like ~SCHEDULED:~."
         "dummy.org" :exact-name
         :line-with-match {:query-string "support+scheduled"}]
        ["constrict by first matching paragraph"
         "xcl:dummy.org?para=what+happen"
         "In 2101, war was beginning. What happen? Main screen turn on. For great justice. Move ZIG."
         "dummy.org" :exact-name
         :paragraph-with-match
         {:query-string "what+happen"}]
        ["constrict by first matching section"
         "xcl:dummy.org?section=famous+script"
         "** famous script\n\n   Captain and CATS\n   \n   In 2101, war was beginning. What happen? Main screen turn on. For great justice. Move ZIG."
         "dummy.org" :exact-name
         :org-section-with-match {:query-string "famous+script"}]
        ["grab text from epub"
         "xcl:alice.epub?p=2-&s=Would you tell me, please...walk long enough"
         (->> ["Would you tell me, please, which way I ought to go from here?’"
               "‘That depends a good deal on where you want to get to,’ said the Cat."
               "‘I don’t much care where—’ said Alice."
               "‘Then it doesn’t matter which way you go,’ said the Cat."
               (str  "‘—so long as I get\n"
                     "somewhere\n"
                     ",’ Alice added as an explanation.")
               "‘Oh, you’re sure to do that,’ said the Cat, ‘if you only walk long enough"]
              (interpose "\n\n")
              (apply str))
         "alice.epub" :exact-name
         nil
         [{:type :page-number
           :bound {:beg 2 :end nil}}
          {:type :token-bound
           :bound {:token-beg "Would you tell me, please"
                   :token-end "walk long enough"}}]]
        ["grab text from pdf"
         "xcl:tracemonkey.pdf?p=3&s=Monkey observes that...so TraceMonkey attempts"
         "Monkey observes that it has reached an inner loop header that al-\nready has a compiled trace, so TraceMonkey attempts"
         "tracemonkey.pdf" :exact-name
         nil
         [{:type :page-number
           :bound {:beg 3 :end 3}}
          {:type :token-bound
           :bound {:token-beg "Monkey observes that"
                   :token-end "so TraceMonkey attempts"}}]]
        ["grab text from json"
         "xcl:test-note-file.json?jsonpath=$[6].content"
         "floating note"
         "test-note-file.json" :exact-name
         nil
         [{:type :jsonpath
           :bound {:jsonpath "$[6].content"}}]]
        ["grab text from yml"
         "xcl:test-highlight-file.yml?jsonpath=$.highlights[2].highlightText"
         "yaml indented text block that spans 2 lines"
         "test-highlight-file.yml" :exact-name
         nil
         [{:type :jsonpath
           :bound {:jsonpath "$.highlights[2].highlightText"}}]]


        ["grab field from csv by column and row, Excel A1 notation"
         "xcl:test-dataset.csv?A1=B4"
         "Diana"
         "test-dataset.csv" :exact-name
         nil
         [{:type :excel-a1
           :bound {:col-number 2:row-number 5}}]]

        ["grab field from tsv by row and key, jq lookup notation"
         "xcl:test-dataset.tsv?jq=.[2].Name"
         "Charlie"
         "test-dataset.tsv" :exact-name
         nil
         [{:type :jq-record-locator
           :bound {:row-index 2 :record-key "Name"}}]]

        ;; need suport an org-mode table notation? something like "xcl:test-dataset.csv?orgtbl=@3$2"

        ["grab field from jsonl by row and key, jq lookup notation"
         "xcl:test-dataset.jsonl?jq=.[2].Name"
         "Charlie"
         "test-dataset.jsonl" :exact-name
         nil
         [{:type :jq-record-locator
           :bound {:row-index 2 :record-key "Name"}}]]

        ;; doesn't work without creating a webserver-side git loader;
        ;; currently a design problem is $ExternalLoaders dispatches
        ;; by file extension and not by protocol. need to fix that
        ;; before browser-side git loading can work
        ;; ["grab field from jsonl by row and key, jq lookup notation"
        ;;  "git:$PWD/.git/blob/54d4f39515cb74a075a0d9201e10cbe344175723/public/test-dataset.jsonl?jq=.[2].Name"
        ;;  "Charlie"
        ;;  "$PWD/.git/blob/54d4f39515cb74a075a0d9201e10cbe344175723/public/test-dataset.jsonl"
        ;;  :git
        ;;  nil
        ;;  [{:type :jq-record-locator
        ;;    :bound {:row-index 2 :record-key "Name"}}]]

        ["raw org text"
         "<<org-text>>::*b-heading"
         "* b-heading  :tag:tiger:\n\n  my text in the b heading"
         nil
         :<<org-text>>
         :org-heading
         {:heading "b-heading"}
         ]
        ["raw org text"
         "<<org-text>>::*a heading"
         "* a heading\n\nnorth star mars car"
         nil
         :<<org-text>>
         :org-heading
         {:heading "a heading"}]
        ]
       (map (fn [[desc
                  link
                  expected-match-text
                  -resolver-path
                  -resolver-method
                  -maybe-main-resolver-type
                  -maybe-main-resolver-bound
                  -additional-merge-map]]
              (let [expected-spec
                    (-> {:resource-resolver-path -resolver-path
                         :resource-resolver-method -resolver-method}
                        (assoc
                         :content-resolvers
                         (if -maybe-main-resolver-type
                           [{:type -maybe-main-resolver-type
                             :bound -maybe-main-resolver-bound}]
                           -maybe-main-resolver-bound))
                        (merge -additional-merge-map))
                    received-match-text (r/atom nil)]

                (resolve-resource-spec-async
                 link
                 (fn on-resolved [resolved-resource-spec]
                   (load-content-async
                    resolved-resource-spec
                    (fn [full-content]
                      (let [resolved-content
                            (some-> (ci/resolve-content
                                     resolved-resource-spec
                                     full-content)
                                    (clojure.string/trim))]
                        (reset! received-match-text
                                resolved-content))))))

                [(fn []
                   (let [received-spec (sc/parse-link link)
                         is-link-match?
                         (->> (keys expected-spec)
                              (map (fn [k]
                                     (= (k expected-spec)
                                        (k received-spec))))
                              (every? identity))

                         is-text-match?
                         (= expected-match-text
                            @received-match-text)

                         success? (and is-link-match?
                                       is-text-match?)]

                     (if (and success?
                              (get-in @view-state [:hide-passing?]))
                       nil
                       ^{:key received-spec}
                       [:tr
                        {:style {:font-size "x-small"}}
                        [:td
                         {:style {:background-color
                                  (case success?
                                    true "lime"
                                    false "red"
                                    "")
                                  :color "white"}}
                         (case success?
                           true "PASS"
                           false "FAIL"
                           "")]
                        [:td desc]
                        [:td [:code link]]
                        [:td (render-map expected-spec)]
                        [:td
                         {:style {:background-color
                                  (case is-link-match?
                                    true "#CFC"
                                    false "#FCC"
                                    "")}}
                         (render-map received-spec)]
                        [:td
                         [:textarea
                          {:style {:font-size "x-small"
                                   :width "100%"
                                   :height "100%"}
                           :value expected-match-text}]]
                        [:td
                         {:style {:background-color
                                  (if @received-match-text
                                    (if (= expected-match-text
                                           @received-match-text)
                                      "#CFC"
                                      "#FCC")
                                    "#CCC")}}
                         [:textarea
                          {:style {:font-size "x-small"
                                   :width "100%"
                                   :height "100%"}
                           :value @received-match-text}]]])))])))
       (concat [:tbody
                [:tr
                 [:th "PASS?"]
                 [:th "description"]
                 [:th "link expression"]
                 [:th "expected resolve"]
                 [:th "resolves to"]
                 [:th "expected content"]
                 [:th "matched content"]]])
       (vec)
       (vector :table
               {:style {:border-collapse "collapse"}}
               [:style "td { border: 1px solid gray; }"])
       (vec)
       (vector :div
               [:h2 "link test view"])))

(defn render-transclusion-test-view! [view-state]
  (let [textarea (fn [content]
                   [:textarea
                    {:style {:width "20em"
                             :height "20em"}
                     :value content}])]
    (->> [["xcl-test-self-recursion.org"
           "I include myself:\nI include myself:\n{{{transclude(xcl:xcl-test-self-recursion.org)}}}"
           nil]
          ["xcl-test-infinite-1.org"
           "Hi from 1. I include infinite 2:\nHi from 2. I include infinite 1:\nHi from 1. I include infinite 2:\n{{{transclude(xcl:xcl-test-infinite-2.org)}}}"
           nil]
          ["xcl-test-1.org"
           "* blah blah\n\ngeneric content\ngenetic content\nanother fake file\n\n* huh\n\nwhatever is in the block"
           nil]
          ["xcl-test-2.org"
           "* fake file 2\n\nrandom block\ntandem block\n-----\n5 SOME LINE\n6 SOME LINE\n7 SOME LINE\n-----\n\n\n-----\nIn 2101, war was beginning. What happen? Main screen turn on. For great justice. Move ZIG.\n-----\n"
           [(fn [s _]
              (str "-----\n"
                   s "\n"
                   "-----\n"))]]
          ["xcl-test-3-c.org"
           "* I am C and I include B

*@1-!!* I am B and I include A

** @2-!!content of A!

aye aye aye??-2@??-1@"
           [(fn [s _]
              (str "!!" s "??"))
            (fn [s _ depth]
              (str "@" depth "-" s "-" depth "@"))]]
          ["xcl-test-3-d.org"
           "* I am D and I include A

#+BEGIN_TRANSCLUSION xcl-test-3-a.org :lines 1
@1 -- content of A!
#+END_TRANSCLUSION
"
           ;; custom transclusion directive postprocessor
           [(fn [s xcl-spec depth]
              (str "#+BEGIN_TRANSCLUSION "
                   (:resource-resolver-path xcl-spec)
                   " :lines "
                   (get-in xcl-spec
                           [:content-resolvers 0 :bound :beg])
                   "\n"
                   "@" depth " -- " s "\n"
                   "#+END_TRANSCLUSION\n"))]]
          ["xcl-test-rewrite.org"
           "relpath rewrite filter?\n\nI have a link to another file:\nglider\n\nyou can [[file:sub/directory/more/fly-away.org]] with me\n\nI can [[sub/directory/more/fly-away.org]] with you\n\n[[fly-away]]\n\nthe end."
           nil]]
         (map (fn [[source-file expected postprocessor-coll]]
                (let [source-text (get-static-content source-file)
                      rendered (apply
                                sc/render-transclusion
                                (fn [& _]
                                  (corpus/list-files "_test"))
                                get-static-content
                                source-text
                                postprocessor-coll)
                      is-same? (= expected rendered)]
                  [(fn []
                     (if (and (get-in @view-state [:hide-passing?])
                              is-same?)
                       nil
                       ^{:key source-file}
                       [:tr
                        [:td
                         {:style {:color "white"
                                  :background (if is-same?
                                                "lime"
                                                "red")}}
                         [:b source-file]]
                        [:td (textarea source-text)]
                        [:td (textarea expected)]
                        [:td (textarea rendered)]]))])))
         (concat [:tbody
                  [:tr
                   (->> ["source file"
                         "source text"
                         "expected"
                         "rendered"]
                        (map (fn [hdr]
                               ^{:key (str "th-" hdr)}
                               [:th hdr])))]])
         (vec)
         (vector :table)
         (vec)
         (vector :div
                 [:h2 "transclusion test"]))))


(defn render-resolver-playground-view! [view-state]
  [(fn []
     [:div
      [:h2 "playground"]
      [:div
       [:select
        {:style {:width "60%"}
         :on-change
         (fn [evt]
           (let [value (aget evt "target" "value")]
             (when-not (empty? value)
               (swap! view-state assoc-in
                      [:resolver-input] value))))}
        [:option
         {:value nil}
         "-- choose an example --"]
        (->> ["file:README.org::7-20"
              "file:README.org::this will watch...recompiles the tests"
              (str "git:"
                   (env/get :user.dir)
                   "/../blob/cdc2252c9740526f7e990855795c296a24bb4991/README.org::There is 1 goal...link for transclusion.")
              ]

             (map (fn [val]
                    ^{:key (str "option-" val)}
                    [:option {:value val} val])))]]
      [:div
       [:input
        {:type "text"
         :style {:width "50%"}
         :value (get-in @view-state [:resolver-input])
         :placeholder "enter a content resolver address or pick an example"
         :on-change (fn [evt]
                      (swap! view-state assoc-in
                             [:resolver-input]
                             (aget evt "target" "value")))}]
       [:button
        {:type "button"
         :on-click (fn []
                     (let [link (get-in @view-state [:resolver-input])]
                       (when-not (empty? link)
                         (let [resolved-link (sc/parse-link link)]
                           (swap! view-state assoc-in
                                  [:resolved-link] resolved-link)
                           (ajax-request
                            {:uri $JSONRPC-SERVER-ENDPOINT
                             :method :post
                             :params {:id 1
                                      :method "get-text"
                                      ;; :params {:protocol "file" :directive "README.org::5"}
                                      :params (merge
                                               {:directive (:link resolved-link)}
                                               resolved-link)
                                      }
                             :format (ajax.core/json-request-format)
                             :response-format (ajax.core/json-response-format
                                               {:keywords? true})
                             :handler (fn [[ok jsonrpc-response]]
                                        (when ok
                                          (swap! view-state assoc-in
                                                 [:resolver-result] (:result jsonrpc-response))))})))))}
        "submit"]]
      (let [resolved-link (get-in @view-state [:resolved-link])
            resolver-result (get-in @view-state [:resolver-result])]
        [:div
         [:div
          (when resolved-link
            (render-map resolved-link))]
         [:pre
          {:style {:border "1px solid #CCC"
                   :border-radius "0.5em"
                   :width "60%"
                   :min-height "2em"
                   :white-space "pre-wrap"
                   :color (if resolver-result
                            "#333"
                            "#CCC")}}
          (or (:text resolver-result)
              "resolver result will output here...")]])])])

(defn main []
  (let [view-state (r/atom {:hide-passing? false})]
    (rdom/render
     [:div
      [:div
       [:label
        [:input
         {:type "checkbox"
          :on-change #(swap! view-state update :hide-passing? not)}]
        "hide passing?"]]
      [:div (render-resolver-playground-view! view-state)]
      [:div (render-resource-resolver-test-view! view-state)]
      [:div (render-link-test-view! view-state)]
      [:div (render-transclusion-test-view! view-state)]
      [:div (render-text-anchoring-test-view! view-state)]
      [:div {:style {:clear "both"}}]]
     (gdom/getElement "main-app"))))

(main)
