(require fetch-url)

(defn fetch-gitlab
  [& {:keys [owner repo rev hash]}]
  (let [archive (str repo "-" rev ".tar.gz")]
    (fetch-url
     :name archive
     :hash hash
     :url (format
           "https://gitlab.com/%s/%s/-/archive/%s/%s"
           owner
           repo
           rev
           archive))))
