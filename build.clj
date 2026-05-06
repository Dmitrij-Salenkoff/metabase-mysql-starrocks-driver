(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'com.starrocks/mysql-starrocks-metabase-driver)
(def version "1.0.0")
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def uber-file (format "target/mysql-starrocks.metabase-driver.jar"))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  ;; Copy source and resources
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  ;; Create the JAR (source-only, Metabase will compile at runtime)
  (b/jar {:class-dir class-dir
          :jar-file uber-file}))

(defn uber [_]
  (clean nil)
  ;; Copy source and resources
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  ;; Create uber JAR with all dependencies
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis}))
