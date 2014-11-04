(ns pivotal-time-tracker.core
  (:gen-class)
  (:require [org.httpkit.client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv]
            [clojure.tools.cli :refer [parse-opts]]
            [simple-time.core :as time]))

(def token "a2a78107444c396b83864db22e31231c")
(def project-ids [1114116, 1114928, 908458])

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
                :updated_after (when start (time/datetime->epoch (apply time/datetime start)))
                :updated_before (when end (time/datetime->epoch (apply time/datetime end)))
                :limit limit
                :offset offset
                :fields "id,project_id,labels,story_type,current_state,name,estimate"}
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

(defn data->csv
  [filename data]
  (let [rows (->> data
                  (map #(vector (key %) (val %)))
                  (into [["Project" "Hours"]]))]
    (with-open [out-file (io/writer filename)]
      (csv/write-csv out-file rows))))

(defn run
  [start, end]
  (->> project-ids
       (map #(get-tickets :project-id % :start start :end end))
       flatten
       (map #(map (fn [x] (assoc x :estimate (:estimate %)
                                   :ticket-id (:id %)))
                  (:labels %)))
       flatten
       (filter #(.startsWith (:name %) *label-prefix*))
       (reduce #(assoc %1 (:name %2) (+ (:name %1 0) (if (nil? (:estimate %2)) 1 (:estimate %2)))) {})
       (map #(hash-map (label->name (key %)) (points->hours (val %))))
       (into {})))

(defn process-date
  [datestr]
  (if datestr
      (->> (clojure.string/split datestr #"\-")
           (map #(Integer/parseInt %)))
      false))

(defn gen-outfile
  [start end]
  (str "./pt-time-" start "-to-" end ".csv"))

(def cli-options
  [["-h" "--help"]
   ["-v" "--verbose"]
   ["-s" "--start START" "Start date: 2014-09-24"
    :default nil
    :validate [#(> (count (process-date %)) 2) "Invalid date format. Example: 2014-06-22"]]
   ["-e" "--end END" "End date: 2014-10-24"
    :default nil
    :validate [#(> (count (process-date %)) 2) "Invalid date format. Example: 2014-06-22"]]
   ["-p" "--prefix PREFIX" "Ticket label prefix."
    :default "tt-"]
   ["-o" "--outfile FILE" "Output csv file."
    :default nil
    :validate [#(contains? #{"csv"} (last (clojure.string/split % #"\."))) "Must be .csv file."]]])

(defn -main [& args]
  (let [opts (parse-opts args cli-options)
        flags (:options opts)]
    (when (:errors opts)
          (do (println (:errors opts))
              (System/exit 0)))
    (alter-var-root (var *label-prefix*) (fn [_] (:prefix flags)))
    (alter-var-root (var *verbose*) (fn [_] (:verbose flags)))
    (if (:help flags)
        (do (println (:summary opts))
            (System/exit 0))
        (do (when (or (nil? (:start flags))
                      (nil? (:end flags)))
                  (do (println "Required options are: start, end.")
                      (System/exit 0)))
            (let [outfile (or (:outfile flags)
                              (gen-outfile (:start flags) (:end flags)))]
              (->> (run (process-date (:start flags))
                   (process-date (:end flags)))
                   (data->csv outfile))
              (println (str "Report saved to: " outfile)))))))
