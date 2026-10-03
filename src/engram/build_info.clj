(ns engram.build-info
  "Build-time facts staged into the uberjar for the runtime to read. build.clj
  overwrites `engram/build-info.edn` with the real values when it builds the
  uberjar. A source tree that was not built through build.clj reads the
  checked-in placeholders.

  Add a key here whenever the build knows something the runtime wants, such as a
  git sha or a build timestamp."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def info (edn/read-string (slurp (io/resource "engram/build-info.edn"))))

(def version (:version info))
