(ns engram.main
  (:require [clojure.java.io :as io]
            [engram.system]
            [integrant.core :as ig])
  (:gen-class))

(defn -main [& _]
  (let [system (ig/init (ig/read-string (slurp (io/resource "system.edn"))))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (println "Halting engram...")
                                 (ig/halt! system)
                                 (println "Halted."))))
    (println (str "engram up — components: " (pr-str (vec (keys system)))))
    @(promise)))
