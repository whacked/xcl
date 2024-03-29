(ns xcl.content-interop
  (:require [xcl.common :refer [re-pos get-file-extension]]
            [xcl.xsv-interop :as xsv]
            [xcl.definitions :refer
             [LinkStructure GitResourceAddress]]
            [xcl.external :as ext]))


;; WARNING: no provision for windows
(def $DIRECTORY-SEPARATOR "/")

(defn log [& ss]
  (apply js/console.log ss))

(defn warn [& ss]
  (apply js/console.warn ss))

(defn find-first-matching-string-element [spec string-elements]
  (let [match-tokens
        (some-> (get-in spec [:bound :query-string])
                (clojure.string/lower-case)
                (clojure.string/split #"\+"))]
    (some->> string-elements
             (filter (fn [line]
                       (every?
                        (fn [token]
                          (-> line
                              (clojure.string/lower-case)
                              (clojure.string/index-of token)))
                        match-tokens)))
             (first))))

(defn get-org-heading-positions [org-content]
  (let [fake-padded-content (str "\n" org-content)]
    (->> (re-pos #"\n(\*+)\s+([^\n]+)"
                 fake-padded-content)
         (map (fn [[idx match]]
                [idx (rest match)]))
         (into {}))))

(defn get-org-drawer-data [org-content]
  (let [drawer-pattern #"(\s*):([^:]+):\s*(\S*)\s*"
        lines (clojure.string/split-lines org-content)]
    (loop [remain lines
           current-drawer-name nil
           current-drawer-indent-level nil
           buffer {}
           out {}]
      (if (empty? remain)
        out
        (let [line (first remain)
              maybe-match (re-find drawer-pattern line)]
          (if-not maybe-match
            (if current-drawer-name
              (recur (rest remain)
                     current-drawer-name
                     current-drawer-indent-level
                     (update buffer :text
                             (fn [cur-text-coll]
                               (conj (or cur-text-coll [])
                                     (clojure.string/trim line))))
                     out)
              (recur (rest remain)
                     current-drawer-name
                     current-drawer-indent-level
                     buffer
                     out))
            (let [[indentation key-name maybe-value]
                  (rest maybe-match)]
              ;; (println ">> KEY" key-name ":::" maybe-value)
              (cond (nil? current-drawer-name)
                    ;; open a new drawer
                    (recur (rest remain)
                           (keyword key-name)
                           (count indentation)
                           {} out)

                    (and current-drawer-name (= key-name "END"))
                    ;; complete the drawer
                    (recur (rest remain)
                           nil
                           nil
                           {}
                           (assoc out current-drawer-name buffer))

                    (and current-drawer-name
                         (= (count indentation)
                            current-drawer-indent-level))
                    ;; collect the attribute
                    (recur (rest remain)
                           current-drawer-name
                           current-drawer-indent-level
                           (assoc buffer (keyword key-name) maybe-value)
                           out)

                    :else
                    (recur (rest remain)
                           current-drawer-name
                           current-drawer-indent-level
                           buffer
                           out)))))))))

;;; RECORD RESOLVERS

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; jq-style record resolver ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TODO: refactor this wrt all the ci/$ and sc/$ atoms
(defn query-by-jq-record-locator
  [records bound-spec]

  (comment
    (let [records [{:name "first" :i 0 "color" "red"}
                   {:name "second" :i 1 "color" "green"}
                   {:name "third" :i 2 "color" "blue"}
                   {:name "fourth" :i 3 "color" "yellow"}]]
      (->> [[{:row-index 2 :record-key "color"} "blue"]
            [{:row-index 5 :record-key "color"} nil]
            [{:row-index -2 :record-key "color"} "blue"]]
           (map-indexed (fn [i [bound-spec expect]]
                          [i (= expect (query-by-jq-record-locator records bound-spec))])))))

  (let [{:keys [row-index record-key]} bound-spec
        nrows (count records)
        effective-row-index (if (< row-index 0)
                              (+ nrows row-index)
                              row-index)]
    effective-row-index
    (if-not (< effective-row-index nrows)
      nil
      (some-> records
              (nth effective-row-index)
              (get record-key)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; excel-a1 record resolver ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TODO: refactor this wrt all the ci/$ and sc/$ atoms
(defn query-by-excel-a1-locator
  [rows bound-spec]
  (comment
    (let [records [["first" 0 "red"]
                   ["second" 1 "green"]
                   ["third" 2 "blue"]
                   ["fourth" 3 "yellow"]]]
      (->> [[{:row-number 3 :col-number 3} "blue"]
            [{:row-number 2 :col-number 9} nil]
            [{:row-number 5 :col-number 1} nil]]
           (map-indexed (fn [i [bound-spec expect]]
                          [i (= expect (query-by-excel-a1-locator
                                        records bound-spec))])))))
  (if (empty? rows)
    nil
    (let [{:keys [row-number col-number]} bound-spec
          nrows (count rows)
          ncols (count (first rows))
          row-index (dec row-number)
          col-index (dec col-number)]
      (if-not (and (< row-index nrows)
                   (< col-index ncols))
        nil
        (some-> rows
                (nth row-index)
                (nth col-index))))))


;; NOTE: consider updating this; these are plain-text resolvers,
;;       i.e. assumed to work within a fully-loaded blob of text.
;;       so these are incompatible with async resource loaders
(def $resolver
  ;; maps from
  ;; <keyword> resolver-identifier -> [resolver-spec string-content full-spec] -> string
  (atom {:whole-file (fn [_ content _] content)
         :line-range (fn [resolver-spec content _]
                       (let [{:keys [beg end]} (:bound resolver-spec)
                             begin-index (max 0 (if beg (dec beg) 0))
                             lines (->> (clojure.string/split-lines content)
                                        (drop begin-index))]
                         (->> (if-not end
                                lines
                                (take (- end begin-index)
                                      lines))
                              (interpose "\n")
                              (apply str))))
         :char-range (fn [resolver-spec content _]
                       (let [{:keys [beg end]} (:bound resolver-spec)]
                         (subs content beg
                               (or end (count content)))))
         :percent-range (fn [resolver-spec content _]
                          (let [lines (clojure.string/split-lines content)
                                n-lines (count lines)
                                {:keys [beg end]} (:bound resolver-spec)
                                beg-index (->> (* 0.01 beg n-lines)
                                               (Math/round)
                                               (max 0))
                                end-index (->> (* 0.01 end n-lines)
                                               (Math/round))]
                            (->> lines
                                 (take (inc end-index))
                                 (drop beg-index)
                                 (interpose "\n")
                                 (apply str))))
         :token-bound (fn [resolver-spec content _]
                        (let [{:keys [token-beg
                                      token-end]}
                              (:bound resolver-spec)
                              lower-content (clojure.string/lower-case
                                             content)

                              re-patternize (fn [token-string]
                                              (->> (clojure.string/split token-string #"\s+")
                                                   (interpose "\\s+")
                                                   (apply str)
                                                   (re-pattern)))

                              maybe-begin-index (some-> token-beg
                                                        (clojure.string/lower-case)
                                                        (re-patternize)
                                                        (re-pos lower-content)
                                                        (keys)
                                                        (first))]
                          (when maybe-begin-index
                            (when-let [maybe-end-index
                                       (some-> token-end
                                               (clojure.string/lower-case)
                                               (re-patternize)
                                               (re-pos (subs lower-content maybe-begin-index))
                                               (keys)
                                               (first)
                                               (+ maybe-begin-index))]
                              (subs content
                                    maybe-begin-index
                                    (+ maybe-end-index (count token-end)))))))

         :line-with-match (fn [resolver-spec content _]
                            (find-first-matching-string-element
                             resolver-spec (clojure.string/split-lines content)))

         :paragraph-with-match (fn [resolver-spec content _]
                                 (find-first-matching-string-element
                                  resolver-spec (clojure.string/split content #"[\r\t ]*\n[\r\t ]*\n[\r\t ]*")))
         :org-heading (fn [resolver-spec content _]
                        (let [target-heading (-> resolver-spec (get-in [:bound :heading]))
                              org-heading-positions (get-org-heading-positions content)]
                          (when-not (empty? org-heading-positions)
                            (loop [remain (sort (keys org-heading-positions))
                                   match-level nil
                                   beg nil
                                   end nil]
                              (if (or (empty? remain)
                                      (and match-level beg end))
                                (subs content beg (or end (count content)))
                                (let [index (first remain)
                                      [stars full-heading-text]
                                      (org-heading-positions index)

                                      ;; strip tags
                                      heading-text (-> full-heading-text
                                                       (clojure.string/replace
                                                        #"\s+:[\w :]+:\s*$" ""))]
                                  (if beg
                                    (if (<= (count stars) match-level)
                                      ;; exit at this found index
                                      (recur (rest remain) match-level beg index)
                                      ;; continue propogate end (nil)
                                      (recur (rest remain) match-level beg end))
                                    (if (= target-heading heading-text)
                                      ;; after finding a match,
                                      ;; we need to continue to the section end
                                      (recur (rest remain) (count stars) index end)
                                      (recur (rest remain) match-level beg end)))))))))
         :org-section-with-match
         (fn [resolver-spec content _]
           (let [match-pattern (-> resolver-spec
                                   (get-in [:bound :query-string])
                                   (clojure.string/lower-case)
                                   (clojure.string/replace #"\+" "\\s+.*?")
                                   (re-pattern))
                 org-heading-positions (get-org-heading-positions
                                        content)]
             (let [maybe-match (re-pos match-pattern content)]
               (when-not (empty? maybe-match)
                 (let [match-index (first (keys maybe-match))
                       [pre-matches post-matches]
                       (->> (sort (keys org-heading-positions))
                            (map (fn [index]
                                   [index (org-heading-positions index)]))
                            (split-with
                             (fn [[index _]]
                               (< index match-index))))
                       [parent-index [stars heading-text]] (last pre-matches)
                       parent-level (count stars)]
                   (loop [remain post-matches
                          end-index nil]
                     (if (or (empty? remain)
                             end-index)
                       (subs content parent-index
                             (or end-index (count content)))
                       (recur (rest remain)
                              (let [[index [stars _]] (first remain)
                                    heading-level (count stars)]
                                (if (<= heading-level parent-level)
                                  index
                                  end-index))))))))))
         :org-node-id
         (fn [resolver-spec content _]
           (let [target-custom-id (-> resolver-spec
                                      (get-in [:bound :id])
                                      (clojure.string/lower-case))
                 org-heading-positions (get-org-heading-positions
                                        content)
                 heading-index-coll (map first org-heading-positions)
                 section-index-pair-coll (map vector
                                              heading-index-coll
                                              (concat (rest heading-index-coll)
                                                      [(count content)]))]
             (loop [remain section-index-pair-coll]
               (let [[section-beg section-end] (first remain)
                     section-text (subs content section-beg section-end)
                     drawer-data (get-org-drawer-data section-text)]
                 (when-not (empty? remain)
                   (if (= target-custom-id
                          (get-in drawer-data
                                  [:PROPERTIES :CUSTOM_ID]))
                     section-text
                     (recur (rest remain))))))))

         :excel-a1
         (fn [resolver-spec content full-spec]
           (let [resource-resolver-path (:resource-resolver-path full-spec)
                 delimiter (-> resource-resolver-path
                               (xsv/get-delimiter-from-file-extension))
                 bound-spec (:bound resolver-spec)]

             (-> content
                 (xsv/parse-xsv delimiter false)
                 (query-by-excel-a1-locator bound-spec))))

         :jq-record-locator
         (fn [resolver-spec content full-spec]
           ;; UGLY! what's a better way to unify the path lookup/dispatch?
           ;;       note that these resolvers can be called after a "plain" resource resolution (LinkStructure)
           ;;       and also a git protocol resolution (GitResourceAddress)
           (let [resource-resolver-path (:resource-resolver-path full-spec)
                 bound-spec (:bound resolver-spec)]

             (case (get-file-extension resource-resolver-path)
               ("csv" "tsv")
               (let [delimiter (-> resource-resolver-path
                                   (xsv/get-delimiter-from-file-extension))]
                 (-> content
                     (xsv/parse-xsv delimiter true)
                     (query-by-jq-record-locator bound-spec)))

               "jsonl"
               (-> content
                   (ext/read-jsonl)
                   (query-by-jq-record-locator bound-spec)))))}))

(defn get-resolver [resolver-spec]
  (if-let [registered-resolver (@$resolver
                                (:type resolver-spec))]
    registered-resolver
    (do
      (warn "UNKNOWN RESOLVER"
            (pr-str (:type resolver-spec))
            " in "
            (pr-str resolver-spec)
            "\nsupported resolvers:"
            (->> (keys @$resolver)
                 (map (fn [s] (str "\n- " s)))
                 (apply str)))
      (@$resolver :whole-file))))


(def $known-post-processors
  (atom {"rewrite-relative-paths"
         (fn [content resolved-spec]
           (let [maybe-directory
                 (->> (clojure.string/split
                       (:resource-resolver-path resolved-spec)
                       $DIRECTORY-SEPARATOR)
                      (drop-last)
                      (interpose $DIRECTORY-SEPARATOR)
                      (apply str))
                 resource-base-directory (if (empty? maybe-directory)
                                           "."
                                           maybe-directory)
                 replace (fn [s]
                           (clojure.string/replace
                            s
                            #"\[\[(file:)?(.+?)(\.[^\.]+)\]"
                            (str "[[$1" resource-base-directory "/$2$3]")))]
             (->> content
                  (clojure.string/split-lines)
                  (interpose "\n")
                  (apply str)
                  (replace))))}))

(defn resolve-content [resolved-full-spec content]
  {:pre [(instance? LinkStructure resolved-full-spec)]}
  (when content
    (let [final-resolver-spec
          (->> resolved-full-spec
               (:content-resolvers)
               (last))

          resolver (get-resolver final-resolver-spec)]

      (loop [remaining-post-processors
             (:post-processors resolved-full-spec)
             out (resolver final-resolver-spec content resolved-full-spec)]
        (if (empty? remaining-post-processors)
          out
          (let [post-processor-name (first remaining-post-processors)
                post-processor (if-let [func (@$known-post-processors
                                              post-processor-name)]
                                 func
                                 (do (warn (str "NO SUCH POST PROCESSOR: "
                                                post-processor-name
                                                " within "
                                                (keys @$known-post-processors)))
                                     identity))]
            (recur (rest remaining-post-processors)
                   (post-processor out resolved-full-spec))))))))

(def Node-TEXT_NODE (atom 3))

;; using .innerText or .textContent causes <br> tags to be collapsed,
;; and we end up with strange text fragments.  couldn't find an easy
;; way to concatenate all text nodes with extra spacing, so here we
;; use a custom text node collector.
(defn get-all-text
  ([dom-node]
   (get-all-text dom-node []))
  ([dom-node out]
   (if (= (aget dom-node "nodeType")
          @Node-TEXT_NODE)
     (conj out (clojure.string/trim
                (aget dom-node "textContent")))
     (->> (loop [remain (some->> (aget dom-node "childNodes")
                                 (array-seq))
                 collected []]
            (if (empty? remain)
              collected
              (recur (rest remain)
                     (concat collected
                             (get-all-text (first remain) out)))))
          (concat out)
          (vec)))))

(defn get-matching-resolver-bound
  [resource-address bound-type]
  (some->> resource-address
           (:content-resolvers)
           (filter (fn [resolver]
                     (= (:type resolver)
                        bound-type)))
           (first)
           (:bound)))

(defn get-maybe-page-number-bound
  [resource-address]
  (get-matching-resolver-bound
   resource-address :page-number))

(defn get-maybe-jsonpath-bound
  [resource-address]
  (get-matching-resolver-bound
   resource-address :jsonpath))
