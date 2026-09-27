(defn exec-phase
  [& command]
  {:type :exec
   :command (vec command)})

