(require fetch-url)

(defn fetch-github
  [& {:keys [owner repo rev hash]}]
  (let [archive (str repo "-" rev ".tar.gz")]
    (fetch-url
     :name archive
     :hash hash
     :url (format
           "https://github.com/%s/%s/archive/refs/tags/%s.tar.gz"
           owner
           repo
           rev))))

