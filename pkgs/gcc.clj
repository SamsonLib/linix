(require host-tools)

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

   :host-tools
   (host-tools
    :binaries
    ["sh"
     "tar"
     "make"])

   :build
   (gnu-build)

   :meta
   {:license :mit}))

