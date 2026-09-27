(require host-tools)
(require gnu-build)
(require package)
(require fetch-url)

(def musl
  (package
   :name "musl"
   :version "1.2.6"

   :source
   (fetch-url
    :name "musl-1.2.6.tar.gz"
    :url "https://musl.libc.org/releases/musl-1.2.6.tar.gz"
    :hash
    "sha256-d585fd3b613c66151fc3249e8ed44f77020cb5e6c1e635a616d3f9f82460512a")

   :dependencies
   (dependencies)

   ;; musl is the base the store bootstraps from, alongside sh: it is what
   ;; every other package here links against, and it is built with the host's
   ;; sh, tar and make because the store has no shell or make of its own yet.
   ;; Nothing is :from-store for that reason. gzip is named because the source
   ;; is a .tar.gz, which the host's tar decompresses by execing it.
   :host-tools
   (host-tools
    :binaries
    ["sh"
     "tar"
     "make"
     "gzip"])

   :build
   (gnu-build)

   :meta
   {:license :mit}))

