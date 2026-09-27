(ns reader
  (:require [errors :as errors])
  (:import (java.io PushbackReader Reader StringReader)))

(def ^:private eof ::eof)

(defn source
  "Build a source map for read-all. `file` may be nil for a bare string."
  ([text] (source nil text))
  ([file text] {:file file :text text}))

;; Positions
;;
;; `read` gives us no position information, so we track it ourselves. The
;; counting reader tallies the characters the reader pulls off the string, and
;; LispReader leaves us sitting one character past the form it just returned --
;; either because it consumed a closing delimiter, or because it read one
;; character of look-ahead to find the end of a token and then pushed it back.
;; Both cases are recoverable, which gives us an exact offset for every form.

(defn- counting-reader
  "Wrap `delegate`, counting consumed characters into the `consumed` volatile."
  [^Reader delegate consumed]
  (proxy [Reader] []
    (read
      ([]
       (let [c (.read delegate)]
         (when (nat-int? c) (vswap! consumed inc))
         c))
      ([buffer offset length]
       (let [n (.read delegate buffer offset length)]
         (when (nat-int? n) (vswap! consumed + n))
         n)))
    (close [] (.close delegate))))

(defn- line-starts
  "Offsets at which each line of `text` begins."
  [text]
  (let [n (count text)]
    (loop [i 0
           starts [0]]
      (if (< i n)
        (recur (inc i)
               (if (= \newline (.charAt ^String text i))
                 (conj starts (inc i))
                 starts))
        starts))))

(defn- line-col
  "Convert a 0-based character offset into a 1-based [line col] pair."
  [starts offset]
  (let [idx (dec (count (take-while #(<= % offset) starts)))]
    [(inc idx) (inc (- offset (nth starts idx)))]))

(defn- line-text
  "The text of the 1-based `line`, without its trailing newline."
  [text starts line]
  (let [from (nth starts (dec line) 0)
        to   (min (count text) (nth starts line (count text)))]
    (subs text from to)))

(defn- skip-to-eol
  [text i]
  (let [n (count text)]
    (loop [i i]
      (if (and (< i n) (not= \newline (.charAt ^String text i)))
        (recur (inc i))
        i))))

(defn- skip-blanks
  "Advance past whitespace and line comments, starting at offset `i`."
  [text i]
  (let [n (count text)]
    (loop [i i]
      (cond
        (>= i n)                                 i
        (Character/isWhitespace ^char (.charAt ^String text i)) (recur (inc i))
        (= \; (.charAt ^String text i))          (recur (skip-to-eol text (inc i)))
        :else                                     i))))

(defn- form-end
  "The offset just past `form`, given how many characters have been consumed.

   A form closed by a delimiter needs no look-ahead, so the consumed count is
   already the end. A scalar ends by reading one character past itself to
   detect the end of the token; that character was pushed back, so drop it."
  [consumed form]
  (if (or (list? form) (vector? form) (map? form) (set? form))
    consumed
    (dec consumed)))

(defn- caret
  "Render `text` with a caret under 1-based column `col`."
  [text col]
  (str "\n  " text "\n  "
       (apply str (repeat (max 0 (dec (or col 1))) \space))
       "^"))

(defn- read-form
  "Read one form, turning reader failures into a located :read-error.

   The reported position is the last character the reader reached, so it
   points at the text that made the read fail."
  [reader text starts file consumed]
  (try
    (read {:eof eof
           :read-cond :allow
           :features #{:clj}}
          reader)
    (catch RuntimeException e
      (let [n     (count text)
            ;; The failure happened on the last character the reader reached.
            ;; Step back over any trailing newline so an unterminated form at
            ;; the end of the source is blamed on its last character.
            offset (let [o (max 0 (min (dec @consumed) (dec n)))]
                     (loop [i o]
                       (if (and (pos? i)
                                (contains? #{\newline \return}
                                           (.charAt ^String text i)))
                         (recur (dec i))
                         i)))
            [line col] (line-col starts offset)
            reason (or (ex-message e) (.getSimpleName (class e)))]
        (errors/fail
         :read-error
         (str "Could not read form: " reason
              (caret (line-text text starts line) col))
         {:file file :line line :col col :cause e})))))

(defn read-file
  "Read the file at `filename` into a source map, or report why it could not
   be read."
  [filename]
  (try
    (source filename (slurp filename))
    (catch java.io.IOException e
      (errors/fail :unreadable-file
                   (str "Could not read file " (pr-str filename)
                        " (" (.getSimpleName (class e)) ")")
                   {:file filename :cause e}))))

(defrecord Located
           [loc form])

(defn located
  "Pair every form in `forms` with `loc`.

   Nested forms have no position of their own, so they inherit the position of
   the top-level form that encloses them."
  [loc forms]
  (map #(->Located loc %) forms))

(defn read-all
  "Read every form from a source map of {:file f :text t}.

   Returns a sequence of Located records, each pairing a form with the
   {:file :line :col} of its first character, so that errors raised while
   evaluating it can name a location. Positions are exact for top-level
   forms; nested forms inherit the position of the form that encloses them."
  [{:keys [file text]}]
  (let [consumed (volatile! 0)
        starts   (line-starts text)
        n        (count text)
        reader   (PushbackReader. (counting-reader (StringReader. text) consumed))]
    (loop [forms  []
           cursor 0]
      (let [start (skip-blanks text cursor)]
        (if (>= start n)
          forms
          (let [form (read-form reader text starts file consumed)]
            (if (= form eof)
              forms
              (let [[line col] (line-col starts start)]
                (recur (conj forms (->Located {:file file :line line :col col} form))
                       (max (form-end @consumed form) start))))))))))
