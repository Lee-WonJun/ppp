(ns build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/ppp.jar")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean
  [_]
  (b/delete {:path class-dir})
  (b/delete {:path uber-file}))

(defn uber
  [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :src-dirs ["src"]
                  :class-dir class-dir
                  :ns-compile '[ppp.main]})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'ppp.main})
  {:uber-file uber-file})
