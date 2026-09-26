(ns engram.main
  (:require [clojure.java.io :as io]
            [engram.system]
            [integrant.core :as ig]
            [taoensso.telemere :as t])
  (:gen-class))

(defn -main [& _]
  (let [system (ig/init (ig/read-string (slurp (io/resource "system.edn"))))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (t/log! {:level :info :id ::halting} "halting engram")
                                 (ig/halt! system))))
    (t/log! {:level :info :id ::started
             :data {:components (mapv str (keys system))
                    :port (or (System/getenv "PORT") "8080")}}
            "engram up")
    @(promise)))
