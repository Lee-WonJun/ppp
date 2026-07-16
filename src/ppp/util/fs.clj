(ns ppp.util.fs
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import (java.io BufferedInputStream BufferedOutputStream PushbackReader)
           (java.nio.charset StandardCharsets)
           (java.nio.file CopyOption Files LinkOption OpenOption Path StandardCopyOption)
           (java.nio.file.attribute FileAttribute)
           (java.security MessageDigest)
           (java.time Instant)
           (java.util Date HexFormat)
           (java.util.zip GZIPInputStream GZIPOutputStream)))

(def ^:private no-links (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(def ^:private no-attrs (make-array FileAttribute 0))
(def ^:private no-open-options (make-array OpenOption 0))

(declare list-tree)

(defn ensure-dir!
  [^Path path]
  (Files/createDirectories path no-attrs)
  path)

(defn exists?
  [^Path path]
  (Files/exists path no-links))

(defn regular-file?
  [^Path path]
  (Files/isRegularFile path no-links))

(defn symbolic-link?
  [^Path path]
  (Files/isSymbolicLink path))

(defn assert-no-symlinks!
  [^Path root]
  (when (symbolic-link? root)
    (throw (ex-info "Symbolic links are not allowed in session storage"
                    {:code :storage/symlink :path (str root)})))
  (doseq [^Path path (list-tree root)]
    (when (symbolic-link? path)
      (throw (ex-info "Symbolic links are not allowed in session storage"
                      {:code :storage/symlink :path (str path)}))))
  root)

(defn read-text
  [^Path path]
  (Files/readString path StandardCharsets/UTF_8))

(defn read-edn
  [^Path path]
  (with-open [reader (PushbackReader. (io/reader (.toFile path)))]
    (binding [*read-eval* false]
      (edn/read {:eof nil} reader))))

(defn- temp-sibling
  [^Path path]
  (.resolveSibling path (str "." (.getFileName path) ".tmp-" (random-uuid))))

(defn atomic-write-bytes!
  [^Path path ^bytes value]
  (ensure-dir! (.getParent path))
  (let [temp (temp-sibling path)]
    (try
      (Files/write temp value no-open-options)
      (try
        (Files/move temp path
                    (into-array CopyOption
                                [StandardCopyOption/ATOMIC_MOVE
                                 StandardCopyOption/REPLACE_EXISTING]))
        (catch java.nio.file.AtomicMoveNotSupportedException _
          (Files/move temp path
                      (into-array CopyOption
                                  [StandardCopyOption/REPLACE_EXISTING]))))
      (finally
        (Files/deleteIfExists temp))))
  path)

(defn atomic-write-string!
  [^Path path value]
  (atomic-write-bytes! path (.getBytes (str value) StandardCharsets/UTF_8)))

(defn atomic-write-edn!
  [^Path path value]
  (let [edn-value (walk/postwalk
                   (fn [item]
                     (if (instance? Instant item)
                       (Date/from ^Instant item)
                       item))
                   value)]
    (atomic-write-string! path (str (pr-str edn-value) "\n"))))

(defn sha256-bytes
  [^bytes value]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.formatHex (HexFormat/of) (.digest digest value))))

(defn sha256-string
  [value]
  (sha256-bytes (.getBytes (str value) StandardCharsets/UTF_8)))

(defn sha256-file
  [^Path path]
  (sha256-bytes (Files/readAllBytes path)))

(defn list-tree
  [^Path root]
  (if-not (exists? root)
    []
    (with-open [stream (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
      (->> (iterator-seq (.iterator stream))
           (remove #(= root %))
           (sort-by str)
           vec))))

(defn delete-tree!
  [^Path root]
  (when (exists? root)
    (doseq [^Path path (sort-by #(.getNameCount ^Path %) > (list-tree root))]
      (Files/deleteIfExists path))
    (Files/deleteIfExists root))
  nil)

(defn copy-tree!
  [^Path source ^Path destination]
  (assert-no-symlinks! source)
  (delete-tree! destination)
  (ensure-dir! destination)
  (doseq [^Path path (list-tree source)]
    (let [relative (.relativize source path)
          target (.resolve destination relative)]
      (if (Files/isDirectory path no-links)
        (ensure-dir! target)
        (do
          (ensure-dir! (.getParent target))
          (Files/copy path target
                      (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))))
  destination)

(defn move-replacing!
  [^Path source ^Path destination]
  (when (exists? destination)
    (delete-tree! destination))
  (ensure-dir! (.getParent destination))
  (try
    (Files/move source destination
                (into-array CopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    (catch java.nio.file.AtomicMoveNotSupportedException _
      (Files/move source destination
                  (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
  destination)

(defn directory-size
  [^Path root]
  (assert-no-symlinks! root)
  (reduce (fn [total ^Path path]
            (if (regular-file? path)
              (+ total (Files/size path))
              total))
          0
          (list-tree root)))

(defn gzip-file!
  [^Path source ^Path destination]
  (ensure-dir! (.getParent destination))
  (with-open [input (BufferedInputStream. (io/input-stream (.toFile source)))
              output (GZIPOutputStream.
                      (BufferedOutputStream. (io/output-stream (.toFile destination))))]
    (io/copy input output))
  destination)

(defn gunzip-file!
  [^Path source ^Path destination]
  (ensure-dir! (.getParent destination))
  (with-open [input (GZIPInputStream.
                     (BufferedInputStream. (io/input-stream (.toFile source))))
              output (BufferedOutputStream. (io/output-stream (.toFile destination)))]
    (io/copy input output))
  destination)

(defn safe-child
  [^Path root relative]
  (let [relative (str/replace (str relative) "\\" "/")
        candidate (.normalize (.resolve root relative))
        normalized-root (.normalize (.toAbsolutePath root))
        normalized-candidate (.normalize (.toAbsolutePath candidate))]
    (when (or (str/blank? relative)
              (str/starts-with? relative "/")
              (not (.startsWith normalized-candidate normalized-root)))
      (throw (ex-info "Path escapes its allowed root" {:path relative})))
    (loop [cursor normalized-root
           components (seq (iterator-seq
                            (.iterator (.relativize normalized-root
                                                    normalized-candidate))))]
      (when-let [component (first components)]
        (let [next-path (.resolve cursor ^Path component)]
          (when (symbolic-link? next-path)
            (throw (ex-info "Symbolic links are not allowed in session storage"
                            {:code :storage/symlink :path (str next-path)})))
          (recur next-path (next components)))))
    candidate))
