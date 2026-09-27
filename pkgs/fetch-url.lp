(defn fetch-url
  [& {:keys [url name hash]}]
  {:type :fetch
   :method :url
   :name name
   :url url
   :hash hash})

