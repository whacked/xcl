(ns xcl.core
  (:require [cemerick.url :refer [url url-encode url-decode]]
            [xcl.definitions :refer [LinkStructure]]
            [xcl.content-interop :as ci]
            [xcl.common :refer [re-pos conj-if-non-nil
                                get-file-extension]]))

;; TODO
;; [ ] anchor text deriver
;; [ ] anchor text resolver

(def $known-protocols
  (atom
   #{:file
     :git
     :grep
     :xcl}))

(def $in-memory-buffer-types
  (atom
   #{:org-text}))

(def $known-resource-resolver-mapping
  (atom
   {:grep :grep-content
    :org-text :<<org-text>>}))

(def link-matcher
  (-> (str
       "((?:(?!::)(?!\\?).)+)"
       "(::|[?])?(.+)?")
      (re-pattern)))

(defn make-named-matcher [pattern keys & [types]]
  (fn [s]
    (let [matches (rest (re-find pattern s))]
      (if (some identity matches)
        (zipmap keys
                (if-not types
                  matches
                  (map (fn [converter s]
                         (converter s))
                       types matches)))))))

(defn string-to-int [s]
  (when s (js/parseInt s)))

(defn string-to-float [s]
  (when s (js/parseFloat s)))

(defn a1-column-to-int [s]
  (comment  ;; test
    (assert (= (a1-column-to-int "A") 1))
    (assert (= (a1-column-to-int "E") 5))
    (assert (= (a1-column-to-int "Z") 26))
    (assert (= (a1-column-to-int "BZ") 78)))

  (->> s
       (clojure.string/upper-case)
       (map (fn [s]
              (- (.charCodeAt s)
                 65)))
       (reduce (fn [ss char-numeric-value]
                 (+ (* ss 26)
                    char-numeric-value
                    1))
               0)))

(defn a1-row-to-int [s]
  (some-> s
          (string-to-int)
          (inc)))

(defn web-query-to-string [s]
  (some-> s
          (clojure.string/replace #"%20" " ")))

(defmulti get-resource-match
  (fn [_ _ resolved]
    (:resource-resolver-method resolved)))

(defn get-file-exact
  [candidate-seq-loader content-loader resolved]
  (let [file-name (:path resolved)]
    (some->> (candidate-seq-loader)
             (filter (partial = file-name))
             (first))))

(defmethod get-resource-match :exact-name
  [candidate-seq-loader content-loader resolved]
  (get-file-exact candidate-seq-loader content-loader resolved))

(defmethod get-resource-match :exact-name-with-subsection
  [candidate-seq-loader content-loader resolved]
  (get-file-exact candidate-seq-loader content-loader resolved))

(defmethod get-resource-match :glob-name
  [candidate-seq-loader content-loader resolved]
  (let [file-pattern (-> (:path resolved)
                         (clojure.string/replace "*" ".*")
                         (re-pattern))]
    (some->> (candidate-seq-loader)
             (filter (partial re-find file-pattern))
             (first))))

(defmethod get-resource-match :grep-content
  [candidate-seq-loader content-loader resolved]
  (let [grep-pattern (-> (:path resolved)
                         (clojure.string/replace "+" " ")
                         (clojure.string/replace "%20" " ")
                         (re-pattern))]
    (some->> (candidate-seq-loader)
             (filter (fn [fname]
                       (some->> fname
                                (content-loader)
                                (re-find grep-pattern))))
             (first))))

(def org-style-range-matchers
  [[:line-range
    (let [line-range-matcher
          (make-named-matcher #"^(\d+)?(-)?(\d+)?$"
                              [:beg :range-marker :end]
                              [string-to-int
                               identity
                               string-to-int])]
      (fn [s]
        (when-let [match (line-range-matcher s)]
          (if-not (:range-marker match)
            (assoc (select-keys match [:beg])
                   :end (:beg match))
            (select-keys match [:beg :end])))))]
   [:char-range
    (make-named-matcher #"^(\d*)?,(\d*)?$"
                        [:beg :end]
                        [string-to-int string-to-int])]
   [:percent-range
    (let [percent-range-matcher
          (make-named-matcher #"^([.\d]+%)?(-)([.\d]+%)?$"
                              [:beg :range-marker :end]
                              [string-to-float
                               identity
                               string-to-float])]
      (fn [s]
        (when-let [match (percent-range-matcher s)]
          (if-not (:range-marker match)
            (assoc (select-keys match [:beg])
                   :end (:beg match))
            (select-keys match [:beg :end])))))]
   [:org-heading
    (make-named-matcher #"^\*\s*([^\*].+)$" [:heading] [url-decode])]
   [:token-bound
    (make-named-matcher #"^(\S.*)\.\.\.(\S.*)$" [:token-beg :token-end])]
   ])

(def url-style-constrictor-matchers
  [[:org-node-id
    (make-named-matcher #"id=(\S+)" [:id])]

   [:page-number
    (let [page-range-matcher
          (make-named-matcher #"p=(\d+)?(-)?(\d+)?"
                              [:beg :range-marker :end]
                              [string-to-int
                               identity
                               string-to-int])]
      (fn [s]
        (when-let [match (page-range-matcher s)]
          (if-not (:range-marker match)
            (assoc (select-keys match [:beg])
                   :end (:beg match))
            (select-keys match [:beg :end])))))]

   [:token-bound
    (make-named-matcher #"s=(\S.*)\.\.\.(\S.*)"
                        [:token-beg :token-end])]
   [:line-with-match
    (make-named-matcher #"line=(\S+)"
                        [:query-string]
                        [web-query-to-string])]
   [:paragraph-with-match
    (make-named-matcher #"para=(\S+)"
                        [:query-string]
                        [web-query-to-string])]
   [:org-section-with-match
    (make-named-matcher #"section=(\S+)"
                        [:query-string]
                        [web-query-to-string])]
   [:jsonpath
    (make-named-matcher #"jsonpath=(.+)$"
                        [:jsonpath]
                        [identity])]

   [:excel-a1
    (make-named-matcher #"[aA]1=([a-zA-Z])+(\d)+$"
                        [:col-number :row-number]
                        [a1-column-to-int a1-row-to-int])]

   [:jq-record-locator
    (make-named-matcher #"jq=\.\[(\d+)\]\.(\w+)$"
                        [:row-index :record-key]
                        [string-to-int identity])]])

(defn get-resource-resolver-method-for-file-by-type [file-path]
  (case (get-file-extension file-path)
    ("pdf" "epub") :exact-name-with-subsection
    :exact-name))

(defn parse-in-memory-buffer-type [link]
  (cond (clojure.string/starts-with?
         link
         "<<org-text>>")
        :org-text

        :else
        nil))

(defn clean-path
  "
  ./part1/part2         -->  part1/part2
  ../dir1/dir2/../dir3  -->  dir3
  "
  [path]
  (loop [remain (-> path
                    (clojure.string/replace
                     #"^\./+" "")
                    (clojure.string/split
                     #"/+"))
         out []]
    (if (empty? remain)
      (clojure.string/join "/" out)
      (let [part (first remain)]
        (recur (rest remain)
               (if (and (= part "..")
                        (not (empty? out ))
                        (not= ".." (last out)))
                 (vec (butlast out))
                 (conj out part)))))))

(defn parse-content-resolver-string
  "given a set of matchers (e.g. org or url-query), return sequence of resolver specifications"
  [matchers resolver-string]
  (when-not (empty? resolver-string)
    (loop [matcher-remain matchers
           out []]
      (if (empty? matcher-remain)
        out
        (let [[range-type matcher]
              (first matcher-remain)
              maybe-match (matcher resolver-string)]
          (recur
           (rest matcher-remain)
           (if maybe-match
             (conj out
                   {:bound maybe-match
                    :type range-type})
             out)))))))

;; TODO: now we have a defrecord; should extract into higher schema?
(defn parse-link [link]
  (let [protocol-matcher
        (-> (str
             "^\\s*(?:("
             (->> @$known-protocols
                  (map name)
                  (interpose "|")
                  (apply str))
             "):)?(.+)\\s*$")
            (re-pattern))

        [maybe-protocol maybe-remainder]
        (rest (re-find protocol-matcher link))

        maybe-in-memory-buffer-type (when maybe-remainder
                                      (parse-in-memory-buffer-type maybe-remainder))

        protocol (cond maybe-protocol
                       (keyword maybe-protocol)

                       :else
                       (or maybe-in-memory-buffer-type
                           :file))

        remainder-with-post-processors (-> (or maybe-remainder link)
                                           (clojure.string/split #"[|¦]"))
        remainder (first remainder-with-post-processors)
        post-processors (seq (rest remainder-with-post-processors))

        [path maybe-qualifier-separator maybe-qualifier]
        (rest (re-find link-matcher remainder))

        maybe-resolvers (parse-content-resolver-string
                         (case maybe-qualifier-separator
                           "::" org-style-range-matchers
                           "?" url-style-constrictor-matchers
                           nil)
                         maybe-qualifier)]

    (LinkStructure.
     link
     protocol
     ;; resource-resolver-method
     (cond (get @$known-resource-resolver-mapping protocol)
           (@$known-resource-resolver-mapping protocol)

           (re-find #"\*" path)
           :glob-name

           (= :git protocol)
           protocol

           :else
           :exact-name)

     ;; resource-resolver-path
     (if (@$in-memory-buffer-types
          protocol)
       nil
       (-> path
           (clean-path)
           (js/decodeURI)
           (str)))

     ;; content-resolvers
     (or maybe-resolvers [{:type :whole-file}])

     post-processors)))

(defn get-resource-match-async
  [candidate-seq-loader-async
   content-loader-async
   link
   callback-on-received-match]


  (let [resolved (parse-link link)
        resource-resolver-path (:resource-resolver-path resolved)
        resource-resolver-method (:resource-resolver-method resolved)

        -exact-name-matcher (fn [matching-resources]
                              (when-let [match (->> matching-resources
                                                    (filter
                                                     (partial = resource-resolver-path))
                                                    (first))]
                                (callback-on-received-match
                                 (assoc resolved
                                        :resource-resolver-path match))))

        content-matcher-async
        (case resource-resolver-method
          :exact-name -exact-name-matcher

          :glob-name (fn [matching-resources]
                       (let [file-pattern (some-> resource-resolver-path
                                                  (clojure.string/replace "*" ".*")
                                                  (re-pattern))]
                         (when-let [match (when file-pattern
                                            (->> matching-resources
                                                 (filter
                                                  (fn [p] (re-find file-pattern p)))
                                                 (first)))]
                           (callback-on-received-match
                            (assoc resolved
                                   :resource-resolver-path match)))))

          :grep-content (fn [matching-resources]
                          (let [grep-pattern (some-> resource-resolver-path
                                                     (clojure.string/replace "+" " ")
                                                     (clojure.string/replace "%20" " ")
                                                     (re-pattern))
                                maybe-load-content-async!
                                (fn maybe-load-content-async! [candidates]
                                  (when-not (empty? candidates)
                                    (let [candidate-spec (first candidates)
                                          fname (:resource-resolver-path candidate-spec)]
                                      (content-loader-async
                                       (assoc resolved
                                              :resource-resolver-path
                                              fname)
                                       (fn [content]
                                         (if (some-> grep-pattern
                                                     (re-find content))
                                           (callback-on-received-match
                                            candidate-spec)
                                           (maybe-load-content-async!
                                            (rest candidates))))))))]
                            (->> matching-resources
                                 (map (fn [match-name]
                                        (assoc resolved
                                               :resource-resolver-path match-name)))
                                 (maybe-load-content-async!))))

          (do
            (js/console.warn (str "ERROR: unhandled resolver: "
                                  resource-resolver-method "; falling back to :exact-name"))
            -exact-name-matcher))]

    (candidate-seq-loader-async
     resource-resolver-path
     (fn [matching-resources]
       (content-matcher-async matching-resources)))))

(def transclusion-directive-matcher
  #"\{\{\{transclude\(([^\)]+)\)\}\}\}")

(def transclusion-directive-matcher-short
  #"\{\{([^\}]+)\}\}")

(defn parse-triple-brace-transclusion-directive [text]
  (re-pos transclusion-directive-matcher text))

(defn parse-transclusion-directive [text]
  (let [triple-brace-matches (re-pos transclusion-directive-matcher text)
        string-with-deletions
        (loop [introns (->> triple-brace-matches
                            (map (fn [[start-index matches]]
                                   [start-index (count (first matches))]))
                            (sort-by first))
               out []
               previous-index 0]
          (if (empty? introns)
            (->> (if (< previous-index (count text))
                   (conj out (subs text previous-index (count text)))
                   out)
                 (apply str))
            (let [[start match-length] (first introns)]
              (recur (rest introns)
                     (conj out
                           (subs text previous-index start)
                           (apply str (take match-length (repeat "\0"))))
                     (+ start match-length)))))]
    (-> (re-pos transclusion-directive-matcher-short string-with-deletions)
        (merge triple-brace-matches))))

(defn resolve-resource-address [resource-address]
  (if (string? resource-address)
    resource-address
    (:file-name resource-address)))

(defn render-transclusion
  "`candidate-seq-loader` should be a function which
   returns a seq of all the known file names
   `content-loader` should be a function which,
   when passed the :resource-address parameter from a `resolved-spec`,
   returns the full text of the target resource (generally a file)
   `postprocessor-coll` is potentially an iterable of functions
   of type (str, transclusion-spec, depth) -> str

  the postprocessing will be applied after every transclusion operation
  "
  [candidate-seq-loader content-loader
   source-text & postprocessor-coll]
  (let [inner-renderer
        (fn inner-renderer [depth
                            visited?
                            candidate-seq-loader
                            content-loader
                            source-text
                            & postprocessor-coll]
          (loop [remain (parse-transclusion-directive source-text)
                 prev-index 0
                 buffer []]
            (if (empty? remain)
              (->> (if (< prev-index (count source-text))
                     (subs source-text prev-index))
                   (conj buffer)
                   (apply str))
              (let [[match-index [matched-string
                                  matched-path]] (first remain)
                    interim-string (when (< prev-index match-index)
                                     (subs source-text prev-index match-index))
                    resolved-spec (parse-link matched-path)
                    resolved-file-name (resolve-resource-address
                                        (:resource-resolver-path resolved-spec))

                    postprocess
                    (fn [content]
                      (reduce (fn [input postprocessor]
                                (postprocessor
                                 input resolved-spec depth))
                              content
                              postprocessor-coll))

                    maybe-content (content-loader resolved-file-name)
                    ]
                (if (visited? resolved-file-name)
                  source-text
                  (recur (rest remain)
                         (+ match-index (count matched-string))
                         (conj-if-non-nil
                          buffer
                          interim-string
                          (if-let [rendered-string
                                   (when maybe-content
                                     (some-> (ci/resolve-content
                                              resolved-spec
                                              maybe-content)
                                             (clojure.string/trim)))]
                            (postprocess
                             (apply
                              inner-renderer
                              (inc depth)
                              (conj visited? resolved-file-name)
                              candidate-seq-loader
                              content-loader
                              rendered-string
                              postprocessor-coll))
                            matched-string))))))))]
    (apply inner-renderer
           1   ;; first detected transclusion starts at depth 1
           #{} ;; visited?
           candidate-seq-loader content-loader source-text
           postprocessor-coll)))


(defn find-match-candidate-index-in-content
  [token content]
  (loop [re-remain [(re-pattern (str "\\b" token "\\b"))
                    (re-pattern (str "\\b" token))
                    (re-pattern (str token "\\b"))]]
    (if (empty? re-remain)
      nil
      (let [re (first re-remain)
            maybe-match (.exec re content)]
        (if maybe-match
          (aget maybe-match "index")
          (recur (rest re-remain)))))))

(defn find-all-match-candidate-indexes-in-content
  [token content]

  (comment
    (= (find-all-match-candidate-indexes-in-content
        "b" "a b b b c d e f f b b g")
       [2 4 6 18 20]))

  (let [content-length (count content)
        token-length (count token)]
    (loop [out []
           offset 0]
      (if-not (< offset content-length)
        out
        (if-let [maybe-index (find-match-candidate-index-in-content
                              token (subs content offset))]
          (recur (conj out (+ maybe-index offset))
                 (+ offset
                    maybe-index
                    token-length))
          out)))))

(defn find-match-candidate-index-in-content-backwards
  [token content]
  (let [rev-token (clojure.string/reverse token)
        rev-content (clojure.string/reverse content)]
    (when-let [match-index (find-match-candidate-index-in-content
                            rev-token
                            rev-content)]
      (- (count content)
         match-index (count token)))))

(defn get-all-valid-token-match-arrangements
  ([token-matches-coll]
   (if (< 6 (count token-matches-coll))
     ;; compexity explodes fast when N goes up, take a shortcut
     (->> token-matches-coll
          (reductions (fn [earliest-match match-coll]
                        ;; select the lowest-index from coll1 as the lower bound
                        (let [earliest-index (:index earliest-match)]
                          ;; find the lowest in the incoming collection
                          ;; that's above our previous lower bound
                          (->> match-coll
                               (filter (fn [token-match]
                                         (< earliest-index (:index token-match))))
                               (sort-by :index)
                               (first))))
                      {:index -1})
          (rest)
          (vector))
     ;; try all 6! combinations
     (->> (get-all-valid-token-match-arrangements
           token-matches-coll [])
          (flatten)
          (partition (count token-matches-coll))
          (filter (fn [token-match-coll]
                    (loop [tm-remain token-match-coll
                           tm-prev nil]
                      (if (empty? tm-remain)
                        true
                        (let [tm-cur (first tm-remain)]
                          (if (< (:index tm-cur)
                                 (+ (:index tm-prev 0)
                                    (:length tm-prev 0)))
                            false
                            (recur (rest tm-remain)
                                   tm-cur))))))))))
  ([token-matches-coll buf]
   (if (empty? token-matches-coll)
     buf
     (->> (first token-matches-coll)
          (map (fn [token-match]
                 (get-all-valid-token-match-arrangements
                  (rest token-matches-coll)
                  (conj buf token-match))))))))

(defn find-most-compact-token-matches-in-content
  "for complex strings this will be >= O(n^k)
   the only optimization we take here is restricting
   the token search to between the first occurrence
   of the first token, the last occurrence of the
   last token"
  [content tokens]
  (let [maybe-first-token-first-start-index
        (find-match-candidate-index-in-content
         (first tokens) content)

        maybe-last-token-last-start-index
        (find-match-candidate-index-in-content-backwards
         (last tokens) content)]
    (when (and maybe-first-token-first-start-index
               maybe-last-token-last-start-index)
      (let [restricted-substring
            (subs content
                  maybe-first-token-first-start-index
                  (+ maybe-last-token-last-start-index
                     (count (last tokens))))

            all-matches
            (->> tokens
                 (map
                  (fn [token]
                    (->> (find-all-match-candidate-indexes-in-content
                          token restricted-substring)
                         (map (fn [index]
                                {:index (+ index
                                           maybe-first-token-first-start-index)
                                 :token token
                                 :length (count token)}))))))

            all-valid-arrangements
            (get-all-valid-token-match-arrangements all-matches)

            compute-spread (fn [arrangement]
                             (- (:index (last arrangement))
                                (:index (first arrangement))))]
        (->> all-valid-arrangements
             (map (fn [arrangement]
                    [(compute-spread arrangement)
                     arrangement]))
             (sort-by first)
             (first)
             (last))))))

(defn find-successive-tokens-in-content
  "this method simply searches forward for the first match,
   then find the next match, and so on.

  in the inner let form, you can change the match method from a
  boundex regex candidate match (current default) to a plain subtring
  match (using indexOf), which will be faster.

  An example of the matching impact in practice: given
  - tokens 'in ring'
  - content 'looking within stringy rings'

  the substring match, using a non-compact matching method, will match
  'look[in]g within st[ring]y rints', returning
  'looking within stringy'

  the regex matcher's logic will for example:
    - ACCEPT 'in'     because exact match
    - ACCEPT 'spin' because .*in
    - ACCEPT 'inside' because in.*
    - REJECT 'finish' because .*in.*
    - REJECT 'looking' because .*in.*

  thus, using non-compact matching, for the example above, will match
  'looking with[in] stringy [ring]s', returning
  'within stringy rings'

  given a search query of 'in ring', I think the regex matcher
  will give a more intuitive result
  "
  ([content tokens]
   (find-successive-tokens-in-content content tokens 0))
  ([content tokens start-offset]
   (loop [remain tokens
          offset start-offset
          out []]
     (if (empty? remain)
       (if (seq out)
         out
         nil)
       (let [token (first remain)
             content-substring (.substr content offset)

             ;; plain substring match
             ;; maybe-index (.indexOf content-substring token)

             ;; bounded regex candidate match
             maybe-index
             (find-match-candidate-index-in-content
              token content-substring)]
         (if (or (not maybe-index)
                 (= -1 maybe-index))
           out
           (recur (rest remain)
                  (+ offset maybe-index
                     (count token))
                  (conj out
                        {:index (+ offset maybe-index)
                         :length (count token)
                         :token token}))))))))

(defn find-content-matches-by-tokenization
  [content targets & {:keys [compact?]}]
  (loop [remain targets
         start-offset 0
         out []]
    (if (empty? remain)
      out
      (let [target-string (first remain)
            tokens (-> target-string
                       (clojure.string/trim)
                       (clojure.string/split #"\s+"))
            matcher-fn (if compact?
                         find-most-compact-token-matches-in-content
                         find-successive-tokens-in-content)
            full-match-data (some->> (matcher-fn
                                      (subs content start-offset)
                                      tokens)
                                     (map (fn [token-match]
                                            (update token-match :index
                                                    (partial + start-offset)))))]
        (if (not= (count full-match-data)
                  (count tokens))
          out
          (recur (rest remain)
                 (let [{:keys [index length]} (last full-match-data)]
                   (+ index length))
                 (conj out full-match-data)))))))

(defn find-corpus-matches-by-tokenization
  [content-coll target-coll & {:keys [compact?]}]
  (loop [remain-content content-coll
         remain-targets target-coll
         index 0
         out []]
    (if (or (empty? remain-content)
            (empty? remain-targets))
      out
      (let [content (first remain-content)
            maybe-matches
            (find-content-matches-by-tokenization
             content
             remain-targets
             :compact? compact?)

            match-report (some->> maybe-matches
                                  (map vector remain-targets)
                                  (map (fn [[target matches]]
                                         {:target target
                                          :matches matches})))]
        (recur
         (rest remain-content)
         (drop (count maybe-matches)
               remain-targets)
         (inc index)
         (conj out match-report))))))

(defn get-resolved-substring-from-tokens
  [text-content tokens]
  (let [maybe-matches (find-successive-tokens-in-content
                       text-content tokens)]
    (when (= (count maybe-matches)
             (count tokens))
      (subs
       text-content
       (:index (first maybe-matches))
       (+ (:index (last maybe-matches))
          (:length (last maybe-matches)))))))
