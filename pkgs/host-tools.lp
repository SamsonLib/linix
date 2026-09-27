(defn host-tools
  [& {:keys [binaries libraries from-store]
      :or {binaries []
           libraries []
           from-store []}}]
  ;; :binaries is everything the phases expect to run: the ones this store
  ;; builds for itself, the ones that can only come from the host (a compiler,
  ;; and gzip to unpack a .tar.gz through tar), and the bootstrap tools at the
  ;; bottom of the chain.
  ;;
  ;; :from-store narrows that to the ones that must come out of the store
  ;; rather than the host. A tool named in both is a claim that the package is
  ;; built with this store's copy, and a build that would quietly fall back to
  ;; the host's is the mistake worth failing on.
  {:type :host-tools
   :binaries (vec binaries)
   :libraries (vec libraries)
   :from-store (vec from-store)})
