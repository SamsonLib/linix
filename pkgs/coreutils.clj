(require sh)
(require musl)
(require make)
(require tar)
(require host-tools)
(require gnu-build)
(require package)
(require dependencies)
(require fetch-url)

;; coreutils.
;;
;; The heaviest of the four and the first that needs no host tool of its own to
;; exist: sh, tar and make are all in the store by now, so this is unpacked by
;; the store's tar and built by the store's make under the store's shell. The
;; only things it still takes from the host are the musl cross compiler, which
;; nothing here packages, and gzip, which tar execs to decompress the source.
;;
;; That is the point of building it: everything above this line in the store is
;; unpacked, configured and compiled without a single program from the host
;; platform, apart from the compiler itself and the decompressor behind tar.
;;
;; coreutils is what supplies the utilities a build phase reaches for that
;; this store does not otherwise have -- install, cp, mkdir, rm, sed, awk,
;; grep, mktemp. They are not declared as :from-store below because a build
;; finds them by name on PATH rather than being told about them; putting this
;; package in a build's :dependencies is what puts them in reach.

(def coreutils
  (package
   :name "coreutils"
   :version "9.5"

   :source
   (fetch-url
    :name "coreutils-9.5.tar.gz"
    :url "https://ftp.gnu.org/gnu/coreutils/coreutils-9.5.tar.gz"
    :hash
    "sha256-767ae6a22950ec42f3ba5f7c1de79dd27800ee8e9b8642da5dedb5974a1741e5")

   ;; musl is named directly rather than inherited through sh, make or tar:
   ;; the core takes its include and link flags from the packages a build
   ;; declares, so this is what puts the store's headers, the store's libc.so
   ;; in the dynamic linker slot, and the store's rpath into the build. Left
   ;; implicit, every one of the 106 binaries here would link against the musl
   ;; inside the compiler wrapper and carry a host path in its ELF header.
   :dependencies
   (dependencies
    :build
    [sh make tar]
    :runtime
    [sh musl])

   :host-tools
   (host-tools
    :binaries
    ["sh"
     "tar"
     "make"
     "gzip"
     "x86_64-unknown-linux-musl-gcc"]

    :from-store
    ["sh" "tar" "make"])

   :build
   (gnu-build
    :environment
    {"CC" "x86_64-unknown-linux-musl-gcc"})

   :meta
   {:license :gpl3Plus}))
