(require package)
(require fetch-gitlab)
(require musl)
(require sh)
(require make)
(require tar)
(require coreutils)

(def tree
  (package
   :name "tree"
   :version "2.3.2"

   :source
   (fetch-gitlab
    :owner "OldManProgrammer"
    :repo "unix-tree"
    :rev "2.3.2"
    :hash
    "sha256-513a53cbc42ca1f4ea06af2bab1f5283524a3848266b1d162416f8033afc4985")

   ;; sh, tar and make are build dependencies because the store builds them,
   ;; and this package should be built with the store's copies rather than the
   ;; host's. coreutils is here for one binary: the Makefile installs with
   ;; $(INSTALL), which is install(1), and without this dependency the install
   ;; phase would quietly use the host's. It is a build dependency only --
   ;; tree links against musl and needs nothing else once it is built.
   :dependencies
   (dependencies
    :build
    [sh tar make coreutils]
    :runtime
    [musl])

   ;; install is named and marked :from-store to hold the coreutils dependency
   ;; to its claim: the Makefile runs it, and a build that fell back to the
   ;; host's would install with a program this store does not know about. The
   ;; compiler is the one tool here that can only come from the host.
   :host-tools
   (host-tools
    :binaries
    ["sh"
     "tar"
     "make"
     "install"
     "x86_64-unknown-linux-musl-gcc"]

    :from-store
    ["sh" "tar" "make" "install"])

   ;; unix-tree ships a Makefile and no configure script, and installs under
   ;; PREFIX, so the configure phase is dropped and install gets $out.
   ;;
   ;; musl alone only puts its headers and libraries on the include and link
   ;; paths; the compiler has to be told to target musl as well, or the host
   ;; cc links against glibc and the runtime dependency has no effect. The
   ;; cross compiler is a libc-less one, which takes the C runtime it links
   ;; against from the musl in the store.
   :build
   (gnu-build
    :environment
    {"CC" "x86_64-unknown-linux-musl-gcc"}

    :phases
    {:configure nil
     :install (shell-phase "make install PREFIX=$out")})

   :meta
   {:license :gpl2Plus}))

(pprint tree)
(println "---------------------------")
(println "building")
(println "---------------------------")
(build tree)
