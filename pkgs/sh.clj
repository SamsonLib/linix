(require musl)
(require host-tools)
(require gnu-build)
(require package)
(require dependencies)
(require shell-phase)
(require fetch-url)

;; The shell every other package in the store is built with.
;;
;; This is the bottom of the toolchain, so it cannot be built with the store's
;; own sh, tar or make -- each of those is needed to unpack and build this one.
;; It therefore takes the host's copies, and every package above it takes this
;; one instead. That is the bootstrap, and it is why this package alone has no
;; :from-store tools: the first link in the chain has nothing to take them
;; from. gzip stays a host tool throughout the store, because tar shells out to
;; it to unpack a .tar.gz and no package here provides it.
;;
;; The package is named sh and installs an sh symlink next to bash. Every
;; other package asks for a tool called sh, and the core looks for that name in
;; the store before the host's PATH, so the symlink is what makes the rest of
;; the store pick this shell up rather than the host's.

(def sh
  (package
   :name "sh"
   :version "5.3"

   :source
   (fetch-url
    :name "bash-5.3.tar.gz"
    :url "https://ftp.gnu.org/gnu/bash/bash-5.3.tar.gz"
    :hash
    "sha256-0d5cd86965f869a26cf64f4b71be7b96f90a3ba8b3d74e27e8e9d9d5550f31ba")

   :dependencies
   (dependencies
    :runtime
    [musl])

   :host-tools
   (host-tools
    :binaries
    ["sh"
     "tar"
     "make"
     "gzip"
     "ln"
     "x86_64-unknown-linux-musl-gcc"])

   :build
   (gnu-build
    ;; --without-bash-malloc is the flag that makes this package usable at all.
    ;; bash defaults to linking its own libmalloc.a, which defines malloc, and
    ;; against a shared musl that replacement is never initialised: musl's own
    ;; internals call malloc too, and they get NULL back, so bash dies on its
    ;; first setlocale with "xmalloc: locale.c:90: cannot allocate 12 bytes".
    ;; Leaving the allocator to musl is also what the rest of the store wants,
    ;; since every other package here uses musl's.
    ;;
    ;; --without-curses keeps readline and termcap on the copies bash bundles
    ;; in lib/, so the build needs no ncurses in the store. Line editing is not
    ;; something a build phase uses.
    :configure-flags
    ["--without-bash-malloc"
     "--without-curses"]

    :environment
    {"CC" "x86_64-unknown-linux-musl-gcc"}

    ;; bash installs itself as bash. The rest of the store asks for a tool
    ;; named sh, so point that name at it.
    :phases
    {:install
     (shell-phase "make install && ln -s bash $out/bin/sh")})

   :meta
   {:license :gpl3Plus}))
