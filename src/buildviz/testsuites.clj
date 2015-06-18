(ns buildviz.testsuites
  (:require [clojure.xml :as xml]))

(defn- is-failure? [testcase-elem]
  (some #(= :failure (:tag %))
        (:content testcase-elem)))

(defn- is-error? [testcase-elem]
  (some #(= :error (:tag %))
        (:content testcase-elem)))

(defn- item-name [elem]
  (:name (:attrs elem)))

(defn- parse-duration [testcase-elem]
  (if-let [time (:time (:attrs testcase-elem))]
    (Math/round (* 1000 (Float/parseFloat time)))))

(defn- parse-status [testcase-elem]
  (if (is-failure? testcase-elem)
    :fail
    (if (is-error? testcase-elem)
      :error
      :pass)))

(defn- testcase [testcase-elem]
  (let [testcase {:name (item-name testcase-elem)
                  :status (parse-status testcase-elem)}]
    (if-let [duration (parse-duration testcase-elem)]
      (assoc testcase :duration duration)
      testcase)))

(declare parse-testsuite)

(defn- testsuite [testsuite-elem]
  {:name (item-name testsuite-elem)
   :children (map parse-testsuite (:content testsuite-elem))})

(defn- testsuite? [elem]
  (= :testsuite (:tag elem)))

(defn- parse-testsuite [elem]
  (if (testsuite? elem)
    (testsuite elem)
    (testcase elem)))

(defn testsuites-for [junit-xml-result]
  (let [root (xml/parse (java.io.ByteArrayInputStream. (.getBytes junit-xml-result)))]
    (if (= :testsuites (:tag root))
      (map parse-testsuite
           (:content root))
      (list (parse-testsuite root)))))




(declare testsuites-map->list)

(defn- testsuite-entry-for [name children-map]
  {:name name
   :children (testsuites-map->list children-map)})

(defn- testsuites-map->list [testsuites]
  (reduce-kv (fn [suites name children]
               (conj suites (if (contains? children :failedCount)
                              (assoc children :name name)
                              (testsuite-entry-for name children))))
             []
             testsuites))


(defn- rolled-out-testcase [suite-id testcase]
  (let [testcase-id (conj suite-id (:name testcase))
        testcase-content (dissoc testcase :name)]
    (vector testcase-id testcase-content)))

(defn- unroll-testcases-for-suite [parent-suite-id entry]
  (if-let [children (:children entry)]
    (let [suite-name (:name entry)
          suite-id (conj parent-suite-id suite-name)]
      (mapcat (partial unroll-testcases-for-suite suite-id) children))
    (list (rolled-out-testcase parent-suite-id entry))))

(defn- unroll-testcases [testsuites]
  (mapcat (partial unroll-testcases-for-suite []) testsuites))


(defn- failed-testcase-ids [unrolled-testcases]
  (map #(first %)
       (filter #(not= :pass (:status (last %)))
               unrolled-testcases)))


(defn- assoc-testcase-entry [testsuite testcase-id fail-count]
  (let [testcase {(peek testcase-id) {:failedCount fail-count}}
        suite-path (pop testcase-id)]
    (update-in testsuite suite-path merge testcase)))

(defn- build-suite-hierarchy-recursively [testsuite testcase-fail-frequencies]
  (if-let [next-testcase (first testcase-fail-frequencies)]
    (let [testcase-id (key next-testcase)
          fail-count (val next-testcase)]
      (recur
       (assoc-testcase-entry testsuite testcase-id fail-count)
       (rest testcase-fail-frequencies)))
    testsuite))

(defn- build-suite-hierarchy [testcase-fail-frequencies]
  (build-suite-hierarchy-recursively {} testcase-fail-frequencies))


(defn accumulate-testsuite-failures [test-runs]
  (->> (mapcat unroll-testcases test-runs)
       (failed-testcase-ids)
       (frequencies)
       (build-suite-hierarchy)
       (testsuites-map->list)))
