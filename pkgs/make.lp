(require sh)
(require musl)
(require host-tools)
(require gnu-build)
(require package)
(require dependencies)
(require fetch-url)

;; GNU make.
;;
;; Second link in the chain, and the first one that can take a tool from the
;; store: it builds with the sh above it rather than the host's, so the
;; configure script and every recipe already run under the store's shell.
;;
;; It cannot take make from the store, because its build is recursive --
;; SUBDIRS is lib, po and doc -- and it has to be the make that builds the
;; make. It also cannot take tar, for the same reason sh cannot: it needs the
;; host's tar to unpack its own source. Both are why only sh is :from-store
;; here, and why the host's make and tar are still named in :binaries.
;;
;; sh is a runtime dependency as well as a build one. make runs recipe lines
;; through a shell, and although gnu-build hands it $SHELL, that only covers
;; builds going through this store; a make invoked bare would otherwise reach
;; for the host's /bin/sh.

(def make
  (package
   :name "make"
   :version "4.4.1"

   :source
   (fetch-url
    :name "make-4.4.1.tar.gz"
    :url "https://ftp.gnu.org/gnu/make/make-4.4.1.tar.gz"
    :hash
    "sha256-dd16fb1d67bfab79a72f5e8390735c49e3e8e70b4945a15ab1f81ddb78658fb3")

   ;; musl is a runtime dependency, and it has to be named directly: the core
   ;; derives include and link flags from the packages a build declares, not
   ;; from what those packages declare themselves. Naming it only in sh would
   ;; leave this build with no -I and no -L for the store's libc, and the
   ;; compiler would quietly fall back to the musl its own wrapper ships --
   ;; which is a host path, and the one thing these packages exist to remove.
   ;; It is what puts the store's libc.so in the dynamic linker slot and in
   ;; the rpath.
   :dependencies
   (dependencies
    :build
    [sh]
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
    ["sh"])

   :build
   (gnu-build
    ;; Two defines to get a glibc-oriented make building against musl, both
    ;; from the same assumption: make's gnulib sources take glibc's headers at
    ;; their word and fall back to old-style declarations when a function is
    ;; not already a macro. musl declares these as ordinary functions, so the
    ;; fallbacks collide with the real prototypes.
    ;;
    ;; __GNU_LIBRARY__ is what glibc's features.h defines and musl does not.
    ;; make's src/getopt.h only gives getopt a real prototype when it is
    ;; defined; without it make declares "extern int getopt ();", which clashes
    ;; with musl's unistd.h. The other says the same thing to lib/fnmatch.c,
    ;; which skips its own "extern char *getenv ();" only when getenv is
    ;; already a macro -- true on glibc, false on musl.
    ;;
    ;; Both are only consulted by those two fallbacks; every real use of the
    ;; names still resolves to the function. package-env appends the musl
    ;; include and link flags after these, so the store's libc still wins.
    :environment
    {"CC" "x86_64-unknown-linux-musl-gcc"
     "CPPFLAGS" "-D__GNU_LIBRARY__=1 -Dgetenv=getenv"})

   :meta
   {:license :gpl3Plus}))
