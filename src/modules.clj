(ns modules
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [errors :as errors]
            [reader :as reader]))

(defn- module-filename
  "Relative filename for a module symbol, treating the symbol as a path:
   dots (and an optional namespace) become directory separators, e.g.
   'foo.bar -> \"foo/bar.lp\", 'ns/foo.bar -> \"ns/foo/bar.lp\"."
  [ctx module-sym]
  (let [parts (cond-> (str/split (name module-sym) #"\.")
                (namespace module-sym) (->> (cons (namespace module-sym))))]
    (str (str/join "/" parts) "." (:module-ext ctx "lp"))))

(defn- find-module-file
  "First existing java.io.File for `filename` across `dirs`, or nil."
  [dirs filename]
  (some (fn [dir]
          (let [f (io/file dir filename)]
            (when (.exists ^java.io.File f) f)))
        dirs))

(defn- requiring-dir
  "Directory of the file that issued this require, if known."
  [loc]
  (when-let [requiring-file (:file loc)]
    (.getParent (io/file requiring-file))))

(defn resolve-module
  ([ctx module-sym] (resolve-module ctx module-sym nil))
  ([ctx module-sym loc]
   (let [filename    (module-filename ctx module-sym)
         load-paths  (:load-paths ctx ["pkgs/"])
         rel-dir     (requiring-dir loc)
         search-dirs (cond->> load-paths
                       rel-dir (cons rel-dir))
         found       (find-module-file search-dirs filename)]
     (if found
       (.getCanonicalPath ^java.io.File found)
       (errors/fail
        :module-not-found
        (str "Could not find module '" module-sym "' — looked for "
             filename " in " (vec search-dirs))
        (assoc loc :module module-sym :filename filename :searched search-dirs))))))

(defn require-module!
  "Loads `module-sym` exactly once (relative to the requiring file's own
   directory, then `ctx`'s load-paths), evaluating its forms via
   `eval-body`. Always returns nil."
  [ctx module-sym loc eval-body]
  (let [path   (resolve-module ctx module-sym loc)
        loaded (:loaded ctx)]
    (when-not (contains? @loaded path)
      (swap! loaded conj path)
      (eval-body ctx {} (reader/read-all (reader/read-file path))))
    nil))
