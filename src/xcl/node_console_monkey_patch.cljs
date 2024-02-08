(ns xcl.node-console-monkey-patch)

(defonce is-patched? (atom false))
(defn monkey-patch-console! []
  (when-not @is-patched?
    ;; ref https://stackoverflow.com/a/47296370
    (js/console.log "NOW MONKEY PATCHING...")
    (doseq [method-name ["log" "warn" "error"]]
      (let [original-method (aget js/console method-name)]
        (aset js/console method-name
              (fn [& args]
                (try
                  (throw (js/Error.))
                  (catch js/Error e
                    (when (string? (aget e "stack"))

                      (let [initiator
                            (->> (-> (aget e "stack")
                                     (.split "\n")
                                     (array-seq))
                                 (drop-while (fn [line]
                                               (or (not (.match line #"^\s*at "))
                                                   (.match line #"node_console_monkey_patch\.cljs:\d+:\d+"))))
                                 (first))]

                        (.apply original-method
                                js/console
                                (.concat (js/Array.
                                          (str "MOD:" (or initiator "unknown")))
                                         (aget args "arr"))))

                      #_(loop [remaining-lines (-> (aget e "stack")
                                                 (.split "\n"))
                             is-first? true
                             initiator nil]
                        (if (or initiator
                                (empty? remaining-lines))
                          (.apply original-method
                                  js/console
                                  (.concat (js/Array.
                                            (str "MOD:" (or initiator
                                                            "unknown place"))) args))
                          (let [matches (some-> (first remaining-lines)
                                                (.match #"^\s+at\s+(.*)"))]
                            (original-method remaining-lines)
                            (if (and matches (not is-first?))
                              (recur
                               (rest remaining-lines)
                               is-first?
                               (second matches))
                              (recur
                               (rest remaining-lines)
                               false
                               initiator))))))))))))

    (reset! is-patched? true)))

(js/console.log "loading patch")
(monkey-patch-console!)
(js/console.log "patch loaded")
