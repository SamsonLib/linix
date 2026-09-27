(ns builder
  (:require [clojure.pprint :refer [pprint]]
            [clojure.string :as str]))

(defn empty-builder
  []
  {:builtins   {}
   :variables  {}
   :load-paths ["."]
   :module-ext "clj"
   :ready?     false})

(defn with-builtin
  [ctx name f]
  (update ctx :builtins assoc name f))

(defn with-builtins
  [ctx m]
  (update ctx :builtins merge m))

(defn with-var
  [ctx name value]
  (update ctx :variables assoc name value))

(defn with-load-path
  [ctx path]
  (update ctx :load-paths (fnil conj ["."]) path))

(defn with-module-ext
  [ctx ext]
  (assoc ctx :module-ext ext))

(def standard-builtins
  {'println println
   'pprint  pprint
   '+       +
   '-       -
   '*       *
   '/       /
   '=       =
   '<       <
   '>       >
   '<=      <=
   '>=      >=
   'str     str
   'join    str/join
   'inc     inc
   'dec     dec
   'list    list
   'vec     vec
   'vector  vector
   'first   first
   'merge   merge
   'seq     seq
   'format  format
   'rest    rest
   'nth     nth
   'count   count})

(defn with-standard-library
  [ctx]
  (with-builtins ctx standard-builtins))

(defn built?
  [ctx]
  (true? (:ready? ctx)))

(defn build
  [ctx]
  (-> ctx
      (update :variables atom)
      (assoc :loaded (atom #{}))
      (assoc :ready? true)))
