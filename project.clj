(defproject pivotal-time-tracker "1.0"
  :description "Generates a time report based on Pivotal Tracker tickets."
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [cheshire "5.3.1"]
                 [http-kit "2.1.16"]
                 [org.clojure/data.csv "0.1.2"]
                 [org.clojure/tools.cli "0.3.1"]
                 [simple-time "0.1.1"]]
  :main pivotal-time-tracker.core
  :aot [pivotal-time-tracker.core])
