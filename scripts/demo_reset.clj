(ns demo-reset
  (:require [babashka.process :as process]
            [clojure.string :as str]))

(defn- output-lines
  [& command]
  (let [result @(process/process (vec command) {:out :string :err :string})]
    (when-not (zero? (:exit result))
      (throw (ex-info "Could not inspect isolated demo resources"
                      {:command command :exit (:exit result) :error (:err result)})))
    (->> (str/split-lines (:out result))
         (remove str/blank?)
         vec)))

(defn- run-if-any!
  [command resources]
  (when (seq resources)
    (let [result @(process/process (into command resources) {:out :string :err :string})]
      (when-not (zero? (:exit result))
        (throw (ex-info "Could not remove isolated demo resources"
                        {:command command :exit (:exit result) :error (:err result)}))))))

(let [containers (output-lines "docker" "ps" "--all" "--quiet"
                               "--filter" "label=ppp.demo=true")
      volumes (output-lines "docker" "volume" "ls" "--quiet"
                            "--filter" "label=ppp.demo=true")]
  (run-if-any! ["docker" "rm" "--force"] containers)
  (run-if-any! ["docker" "volume" "rm" "--force"] volumes)
  (println (str "Removed " (count containers) " demo container(s) and "
                (count volumes) " demo volume(s). Normal sessions and OAuth were untouched.")))
