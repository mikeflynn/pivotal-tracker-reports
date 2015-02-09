(ns pivotal-time-tracker.core
  (:gen-class)
  (:require [org.httpkit.client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv]
            [clojure.tools.cli :refer [parse-opts]]
            [simple-time.core :as time]))

(defn get-param [n]
  (let [system_env (System/getenv n)
        system_prop (System/getProperty n)]
    (if-let [param (if system_env system_env system_prop)]
      param
      d)))

(def token (get-param "PTT_TOKEN" false))
(def #^{:dynamic true} *all-project-ids* [])
(def #^{:dynamic true} *label-prefix* "tt-")
(def #^{:dynamic true} *verbose* false)
(defn alert [msg] (when *verbose* (println msg)))

(defn clean-params
  "Removes any nil params."
  [params]
  (->> params
       (filter #(not (nil? (val %))))
       (into {})))

(defn get-tickets
  [& {:keys [project-id label state start end limit offset retries return]
      :or {retries 0 limit 100 offset 0}}]
  (when (nil? project-id) (throw (Exception. "Missing project-id.")))
  (let [url (str "https://www.pivotaltracker.com/services/v5/projects/" project-id "/stories")
        params {:with_label label
                :with_state state
                :accepted_after (when start (time/datetime->epoch (apply time/datetime start)))
                :accepted_before (when end (time/datetime->epoch (apply time/datetime end)))
                :limit limit
                :offset offset
                :fields "id,project_id,labels,story_type,current_state,name,estimate,owners"}
        headers {"X-TrackerToken" token}
        response (http/get url {:query-params (clean-params params)
                                :headers headers
                                :timeout 2000})]
    (if (not= 200 (:status @response))
        (if (< retries 0)
            (do
              (alert (str "Error response from " url ". Re-trying..."))
              (get-tickets :retries (+ retries 1)
                           :project-id project-id
                           :label label
                           :start start
                           :end end
                           :limit limit
                           :offset offset
                           :return return))
            (do
              (alert "Max retries hit. Request failed.")
              {:error (get (:body @response) "error")}))
        (try
          (let [body (-> (:body @response)
                         (json/parse-string true))]
            (if (:error body)
                (throw (Exception. (or (get-in body [:error :message]) "Error or blank response from API.")))
                (let [return (into return body)]
                  (if (< (count return) (Integer/parseInt (get-in @response [:headers :x-tracker-pagination-total])))
                      (get-tickets :retries 0
                                   :project-id project-id
                                   :label label
                                   :start start
                                   :end end
                                   :limit limit
                                   :offset (- (count return) 1)
                                   :return return)
                      return))))
          (catch Exception e {:error (.getMessage e)})))))

(defn get-iterations
  [& {:keys [project-id offset label limit scope retries]
      :or {offset 0 limit 5 scope "done" retries 0}}]
  (when (nil? project-id) (throw (Exception. "Missing project-id.")))
  (let [url (str "https://www.pivotaltracker.com/services/v5/projects/" project-id "/iterations")
        params {:label label
                :offset offset
                :limit limit
                :scope scope}
        headers {"X-TrackerToken" token}
        response (http/get url {:query-params (clean-params params)
                                :headers headers
                                :timeout 2000})]
    (if (not= 200 (:status @response))
        (if (< retries 0)
            (do
              (alert (str "Error response from " url ". Re-trying..."))
              (get-tickets :retries (+ retries 1)
                           :label label
                           :offset offset
                           :limit limit
                           :scope scope))
            (do
              (alert "Max retries hit. Request failed.")
              {:error (get (:body @response) "error")}))
        (try
          (let [body (-> (:body @response)
                         (json/parse-string true))]
            (if (:error body)
                (throw (Exception. (or (get-in body [:error :message]) "Error or blank response from API.")))
                body))
          (catch Exception e {:error (.getMessage e)})))))

(defn get-project-roster
  [project-id & {:keys [retries] :or {retries 0}}]
  (when (nil? project-id) (throw (Exception. "Missing project-id.")))
    (let [url (str "https://www.pivotaltracker.com/services/v5/projects/" project-id "/memberships")
        params {}
        headers {"X-TrackerToken" token}
        response (http/get url {:query-params (clean-params params)
                                :headers headers
                                :timeout 2000})]
    (if (not= 200 (:status @response))
        (if (< retries 0)
            (do
              (alert (str "Error response from " url ". Re-trying..."))
              (get-project-roster project-id :retries (+ retries 1)))
            (do
              (alert "Max retries hit. Request failed.")
              {:error (get (:body @response) "error")}))
        (try
          (let [body (-> (:body @response)
                         (json/parse-string true))]
            (if (:error body)
                (throw (Exception. (or (get-in body [:error :message]) "Error or blank response from API.")))
                body))
          (catch Exception e {:error (.getMessage e)})))))

(defn points->hours
  [points]
  (->> (* points 0.625)
       (+ points)
       (format "%.0f")))

(defn label->name
  [label]
  (-> (name label)
      (subs (.length *label-prefix*))
      (clojure.string/replace #"\-" " ")))

(defn map-count [map record]
  (for [x (:labeld record)]
    (assoc map key (inc (get map key 0)))))

(defn pmapcat
  [f batches]
  (->> batches
       (pmap f)
       (apply concat)
       doall))

(defn md5
  "Generate a md5 checksum for the given string"
  [token]
  (let [hash-bytes
         (doto (java.security.MessageDigest/getInstance "MD5")
               (.reset)
               (.update (.getBytes token)))]
       (.toString
         (new java.math.BigInteger 1 (.digest hash-bytes)) ; Positive and the size of the number
         16)))

(defn get-color
  [string]
  (subs (md5 string) 0 6))

(defn data->csv
  [filename data]
  (io/make-parents filename)
  (let [rows (into [["Project" "Employee" "Hours"]] data)]
    (with-open [out-file (io/writer filename)]
      (csv/write-csv out-file rows))))

(defn data->json
  [filename data]
  (io/make-parents filename)
  (with-open [out-file (io/writer filename)]
    (.write out-file (json/generate-string data))))

(defn get-roster
  [project-id]
  (->> (get-project-roster project-id)
       (map #(hash-map (get-in % [:person :id]) (get-in % [:person :name])))
       (into {})))

(defn sum-total-sprint-points
  [tickets]
  (->> tickets
       (filter #(= (:current_state %) "accepted"))
       (reduce #(+ %1 (:estimate %2 0)) 0)))

(defn sum-dev-sprint-points
  [tickets]
  (->> tickets
       (filter #(and (= (:current_state %) "accepted")
                     (first (:owner_ids %))))
       (reduce #(assoc %1 (:owned_by_id %2)
                          (+ (get %1 (:owned_by_id %2) 0)
                             (:estimate %2 0)))
               {})))

(defn sum-dev-sprint-tickets
  [tickets]
  (->> tickets
       (filter #(and (= (:current_state %) "accepted")
                     (first (:owner_ids %))))
       (reduce #(assoc %1 (:owned_by_id %2)
                          (+ (get %1 (:owned_by_id %2) 0) 1))
               {})))

(defn run-time-report
  [start end outfile]
  (->> *all-project-ids*
       (map #(get-tickets :project-id % :start start :end end))
       flatten
       (map #(map (fn [x] (assoc x :estimate (:estimate %)
                                   :ticket-id (:id %)
                                   :owner (->> (:owners %)
                                               first
                                               ((fn [x] (:name x 0))))))
                  (:labels %)))
       flatten
       (filter #(.startsWith (:name %) *label-prefix*))
       (reduce #(assoc-in %1 [(keyword (:name %2)) (keyword (str (:owner %2)))] (+ (get-in %1 [(keyword (:name %2)) (keyword (str (:owner %2)))] 0) (if (nil? (:estimate %2)) 1 (:estimate %2)))) {})
       (map #(into [] (map (fn [x] (vector (name (key %)) (name (key x)) (val x))) (val %))))
       (reduce into [])
       (map #(vector (first %) (second %) (points->hours (nth % 2))))
       (data->csv outfile)))

(defn run-sprint-dev-report
  [project-id report outfile]
  (let [sprints (->> (get-iterations :project-id project-id
                                     :offset -5)
                     (map #(hash-map (:number %) (case report
                                                       :points (sum-dev-sprint-points (:stories %))
                                                       :tickets (sum-dev-sprint-tickets (:stories %)))))
                     (into {}))
        result {"graph" {"title" (str "Sprint " (get (last sprints) "title") " " (name report) " Report") "total" true "refreshEveryNSeconds" (* 60 60 24) "datasequences" []}}
        roster (get-roster project-id)
        devs (->> (vals sprints)
                  (into {})
                  keys
                  (map #(hash-map % (get roster %)))
                  (into {}))]
    (->> (map #(hash-map :title (val %)
                         :color (get-color (val %))
                         :datapoints (into [] (map (fn [x] (hash-map "title" (str (key x))
                                                                     "value" (str (get (val x) (key %) 0)))) sprints))) devs)
         (assoc-in result ["graph" "datasequences"])
         (data->json outfile))))

(defn run-sprint-team-report
  [project-id outfile]
  (let [sprints (->> (get-iterations :project-id project-id
                                     :offset -5)
                     (map #(hash-map (:number %) (sum-total-sprint-points (:stories %))))
                     (into {})
                     (map #(hash-map "title" (str (key %))
                                     "value" (str (val %))))
                     (into []))
       result {"graph" {"title" (str "Sprint " (get (last sprints) "title") " Total Points Report") "refreshEveryNSeconds" (* 60 60 24) "type" "line" "datasequences" []}}]
    (->> sprints
         (hash-map "title" "Points Per Sprint"
                   "datapoints")
         (conj [])
         (assoc-in result ["graph" "datasequences"])
         (data->json outfile))))

(defn run-sprint-reports
  [project-id outdir]
  (run-sprint-team-report project-id (str outdir "/team_points_per_sprint.json"))
  (run-sprint-dev-report project-id :tickets (str outdir "/dev_tickets_per_sprint.json"))
  (run-sprint-dev-report project-id :points (str outdir "/dev_points_per_sprint.json")))

(defn process-date
  [datestr]
  (if datestr
      (->> (clojure.string/split datestr #"\-")
           (map #(Integer/parseInt %)))
      false))

(defn gen-outfile
  [start end]
  (str "/pt-time-" start "-to-" end ".csv"))

(def cli-options
  [["-h" "--help"]
   ["-v" "--verbose"]
   ["-i" "--project-id PROJECT-ID" "The project id, or ids (comma separated)."
    :default nil
    :validate [#(not(empty? %)) "You must enter a project id to continue."]]
   ["-j" "--job JOB" "sprint or timesheet"
    :default "timesheet"]
   ["-s" "--start START" "Start date: 2014-09-24"
    :default nil]
   ["-e" "--end END" "End date: 2014-10-24"
    :default nil]
   ["-p" "--prefix PREFIX" "Ticket label prefix."
    :default "tt-"]
   ["-o" "--outdir DIR" "Output directory."
    :default "/tmp"]])

(defn set-prefix
  [prefix]
  (alter-var-root (var *label-prefix*) (fn [_] prefix)))

(defn set-verbose
  [v]
  (alter-var-root (var *verbose*) (fn [_] v)))

(defn set-project-id
  [project-id]
  (alter-var-root (var *project-ids*) (fn [_] project-id)))

(defn -main [& args]
  (let [opts (parse-opts args cli-options)
        flags (:options opts)]
    (when (:errors opts)
          (do (println (:errors opts))
              (System/exit 0)))
    (set-prefix (:prefix flags))
    (set-verbose (:verbose flags))
    (set-project-id (clojure.string/split (:prefix project-id "") #","))
    (if (:help flags)
        (do (println (:summary opts))
            (System/exit 0))
        (if (= (:job flags) "sprint")
            (doseq [p *project-ids*]
              (run-sprint-reports p (str (:outdir flags) p)))
            (do (when (or (nil? (:start flags))
                          (nil? (:end flags)))
                      (do (println "Required options are: start, end.")
                          (System/exit 0)))
                (run-time-report (process-date (:start flags)) (process-date (:end flags)) (gen-outfile (:start flags) (:end flags))))))))
