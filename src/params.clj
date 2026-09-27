(ns params
  (:require [errors :as errors]))

(defn valid-param?
  [p]
  (or (symbol? p) (map? p)))

(defn destructure-map
  ([m-map m] (destructure-map nil m-map m))
  ([loc m-map m]
   (let [{:keys [keys] defaults :or} m-map
         defaults (or defaults {})]
     (when-not (map? m)
       (errors/fail :not-a-map
                    (str "Cannot destructure " (pr-str m) " with {:keys "
                         (pr-str keys) "} — expected a map")
                    (assoc loc :value m :keys keys)))
     (into {}
           (map (fn [k]
                  [k (let [key (keyword k)]
                       (if (contains? m key)
                         (get m key)
                         (get defaults k)))]))
           keys))))

(defn bind-params
  ([params args] (bind-params nil params args))
  ([loc params args]
   (loop [params params
          args   args
          env    {}]
     (if (empty? params)
       env
       (let [p (first params)]
         (if (= p '&)
           (let [rest-param (second params)
                 remaining  args]
             (merge env
                    (if (map? rest-param)
                      (destructure-map loc rest-param (apply hash-map remaining))
                      {rest-param (vec remaining)})))
           (recur (rest params)
                  (rest args)
                  (merge env
                         (if (map? p)
                           (destructure-map loc p (first args))
                           {p (first args)})))))))))
