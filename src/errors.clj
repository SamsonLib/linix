(ns errors)

(def default-source-name
  "Name used in error messages when the source did not come from a file."
  "<string>")

(defn- location
  "Render a source location map as \"file:line:col\".

   Returns nil when the location carries no line, which is the case for
   errors raised outside of reading or evaluating a form."
  [{:keys [file line col]}]
  (when line
    (str (or file default-source-name) ":" line (when col (str ":" col)))))

(defn- prefix
  "Prepend the source location in `data`, if any, to `message`."
  [data message]
  (if-let [loc (location data)]
    (str loc ": " message)
    message))

(defn fail
  "Throw an ExceptionInfo tagged with a machine-readable `:type`.

   `data` is the ex-data map. When it carries `:file`/`:line`/`:col` the
   location is prepended to the message, so callers never have to format
   locations by hand:

     (errors/fail :unknown-symbol
                  (str \"Unknown symbol: \" sym)
                  (assoc loc :symbol sym))"
  ([type message] (fail type message nil))
  ([type message data]
   (throw (ex-info (prefix data message) (assoc data :type type)))))
