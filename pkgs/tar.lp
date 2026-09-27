(require sh)
(require musl)
(require make)
(require host-tools)
(require gnu-build)
(require package)
(require dependencies)
(require fetch-url)

;; GNU tar.
;;
;; Third link in the chain, and the last one that needs a host tool of its own
;; to exist: it is unpacked by the host's tar, since no package in the store
;; provides tar until this build finishes. Everything it then runs -- the
;; configure script, make, and every recipe -- comes from the store.
;;
;; Its recursive build (SUBDIRS is doc, gnu, lib, rmt, src, scripts, po and
;; tests) is why it is placed after make: it needs a make, and the one it needs
;; is the store's.
;;
;; sh is a runtime dependency because tar runs filters, checkpointing and
;; --to-command through a shell. gzip is named but never :from-store, and stays
;; that way for the rest of the store: tar decompresses a .tar.gz by execing
;; gzip, so gzip has to be reachable, and nothing here provides it.

(def tar
  (package
   :name "tar"
   :version "1.35"

   :source
   (fetch-url
    :name "tar-1.35.tar.gz"
    :url "https://ftp.gnu.org/gnu/tar/tar-1.35.tar.gz"
    :hash
    "sha256-14d55e32063ea9526e057fbf35fcabd53378e769787eff7919c3755b02d2b57e")

   ;; musl is named directly rather than inherited from sh: the core reads the
   ;; include and link flags off the packages this one declares, so a runtime
   ;; dependency is the only thing that puts the store's headers, its libc.so
   ;; in the dynamic linker slot, and its rpath into this build.
   :dependencies
   (dependencies
    :build
    [sh make]
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

    ;; tar is deliberately absent: at the time this builds, the only tar in
    ;; reach is the host's, which is what unpacks this very source tarball.
    :from-store
    ["sh" "make"])

   :build
   (gnu-build
    :environment
    {"CC" "x86_64-unknown-linux-musl-gcc"})

   :meta
   {:license :gpl3Plus}))
