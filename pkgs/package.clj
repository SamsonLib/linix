(require dependencies)
(require default-host-tools)

(defn package
  [& {:keys [name
             version
             source
             dependencies
             host-tools
             build
             meta]
      :or {dependencies (dependencies)
           host-tools default-host-tools
           meta {}}}]
  {:type :package
   :name name
   :version version
   :source source
   :dependencies dependencies
   :host-tools host-tools
   :build build
   :meta meta})


