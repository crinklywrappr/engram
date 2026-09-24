(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]))

(def lib 'com.github.crinklywrappr/engram)
(def version "0.1.0-SNAPSHOT")
(def main 'engram.main)
(def class-dir "target/classes")
(def uber-file (format "target/engram-%s-standalone.jar" version))

;; Datalevin reaches into java.nio, so the compile and test JVMs need these.
(def datalevin-opts ["--add-opens=java.base/java.nio=ALL-UNNAMED"
                     "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"])

(defn test "Run all the tests." [opts]
  (let [basis    (b/create-basis {:aliases [:test]})
        cmds     (b/java-command
                  {:basis     basis
                   :java-opts datalevin-opts
                   :main      'clojure.main
                   :main-args ["-m" "cognitect.test-runner"]})
        {:keys [exit]} (b/process cmds)]
    (when-not (zero? exit) (throw (ex-info "Tests failed" {}))))
  opts)

(defn- uber-opts [opts]
  (assoc opts
         :lib lib :main main
         :uber-file uber-file
         :basis (b/create-basis {})
         :class-dir class-dir
         :src-dirs ["src"]
         :java-opts datalevin-opts
         :ns-compile [main]))

(defn ci "Run the CI pipeline of tests (and build the uberjar)." [opts]
  (test opts)
  (b/delete {:path "target"})
  (let [opts (uber-opts opts)]
    (println "\nCopying source...")
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (println (str "\nCompiling " main "..."))
    (b/compile-clj opts)
    (println "\nBuilding JAR..." (:uber-file opts))
    (b/uber opts))
  opts)
