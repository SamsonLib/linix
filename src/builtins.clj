(ns builtins
  (:use clojure.pprint))

(def builtins
  (atom
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
    'count   count}))

(defn add-builtin!
  "Add or replace a builtin function.

   Example:
     (add-builtin! 'square #( * % % ))"
  [name f]
  (swap! builtins assoc name f))
