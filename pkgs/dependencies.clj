(defn dependencies
  [& {:keys [build runtime]
      :or {build []
           runtime []}}]
  {:build (vec build)
   :runtime (vec runtime)})

