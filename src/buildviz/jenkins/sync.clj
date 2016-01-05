(ns buildviz.jenkins.sync
  (:require [buildviz.jenkins
             [api :as api]
             [transform :as transform]]
            [buildviz.util.json :as json]
            [cheshire.core :as j]
            [clj-http.client :as client]
            [clj-progress.core :as progress]
            [clj-time
             [coerce :as tc]
             [core :as t]
             [format :as tf]
             [local :as l]]
            [clojure.string :as string]
            [clojure.tools
             [cli :refer [parse-opts]]
             [logging :as log]]))

(def tz (t/default-time-zone))

(def date-formatter (tf/formatter tz "YYYY-MM-dd" "YYYY/MM/dd" "YYYYMMdd" "dd.MM.YYYY"))

(def cli-options
  [["-b" "--buildviz URL" "URL pointing to a running buildviz instance"
    :id :buildviz-url
    :default "http://localhost:3000"]
   ["-f" "--from DATE" "Date from which on builds are loaded, if not specified tries to pick up where the last run finished"
    :id :sync-start-time
    :parse-fn #(tf/parse date-formatter %)]
   ["-h" "--help"]])

(defn usage [options-summary]
  (string/join "\n"
               [""
                "Syncs Jenkins build history with buildviz"
                ""
                "Usage: buildviz.jenkins.sync [OPTIONS] JENKINS_URL"
                ""
                "JENKINS_URL            The URL of the Jenkins installation"
                ""
                "Options"
                options-summary]))

(defn add-test-results [jenkins-url {:keys [job-name number] :as build}]
  (assoc build :test-report (api/get-test-report jenkins-url job-name number)))


(defn put-build [buildviz-url job-name build-id build]
  (client/put (string/join [buildviz-url (format "/builds/%s/%s" job-name build-id)])
              {:content-type :json
               :body (json/to-str build)}))

(defn put-test-results [buildviz-url job-name build-id test-results]
  (client/put (string/join [buildviz-url (format "/builds/%s/%s/testresults" job-name build-id)])
              {:content-type :json
               :body (j/generate-string test-results)}))

(defn put-to-buildviz [buildviz-url {:keys [job-name build-id build test-results]}]
  (log/info (format "Syncing %s %s: build" job-name build-id))
  (put-build buildviz-url job-name build-id build)
  (when test-results
    (put-test-results buildviz-url job-name build-id test-results)))


(defn- jenkins-build->start-time [{timestamp :timestamp}]
  (tc/from-long timestamp))

(defn- ignore-ongoing-builds [builds]
  (filter :result builds))

(defn- all-builds-for-job [jenkins-url sync-start-time job-name]
  (let [safe-build-start-time (t/minus sync-start-time (t/millis 1))]
    (->> (api/get-builds jenkins-url job-name)
         (take-while #(t/after? (jenkins-build->start-time %) safe-build-start-time))
         ignore-ongoing-builds)))

(defn- sync-oldest-first-to-deal-with-cancellation [builds]
  (sort-by :timestamp builds))

(defn- sync-jobs [jenkins-url buildviz-url sync-start-time]
  (println "Jenkins" jenkins-url "-> buildviz" buildviz-url)
  (println (format "Finding all builds for syncing (starting from %s)..."
                 (tf/unparse (:date-time tf/formatters) sync-start-time)))
  (->> (api/get-jobs jenkins-url)
       (mapcat #(all-builds-for-job jenkins-url sync-start-time %))
       (progress/init "Syncing")
       sync-oldest-first-to-deal-with-cancellation
       (map (partial add-test-results jenkins-url))
       (map transform/jenkins-build->buildviz-build)
       (map (partial put-to-buildviz buildviz-url))
       (map progress/tick)
       dorun
       (progress/done)))


(def last-week (t/minus (.withTimeAtStartOfDay (l/local-now)) (t/weeks 1)))

(defn- get-latest-synced-build-start [buildviz-url]
  (let [response (client/get (format "%s/status" buildviz-url))
        buildviz-status (j/parse-string (:body response) true)]
    (when-let [latest-build-start (:latestBuildStart buildviz-status)]
      (tc/from-long latest-build-start))))

(defn- get-start-date [buildviz-url date-from-config]
  (if (some? date-from-config)
    date-from-config
    (if-let [latest-sync-build-start (get-latest-synced-build-start buildviz-url)]
      latest-sync-build-start
      last-week)))


(defn -main [& c-args]
  (let [args (parse-opts c-args cli-options)]
    (when (or (:help (:options args))
              (empty? (:arguments args)))
      (println (usage (:summary args)))
      (System/exit 0))

    (let [jenkins-url (first (:arguments args))
          buildviz-url (:buildviz-url (:options args))
          sync-start-time (get-start-date buildviz-url (:sync-start-time (:options args)))]

      (sync-jobs jenkins-url buildviz-url sync-start-time))))