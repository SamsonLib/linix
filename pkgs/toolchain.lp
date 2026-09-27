(require package)
(require dependencies)
(require musl)
(require sh)
(require make)
(require tar)
(require coreutils)

;; Builds the four programs the rest of the store is built with, in the order
;; the chain has to go in.
;;
;; sh, tar and make are mutually dependent -- each is needed to build the
;; others -- so the first three of them build against the host's copies and
;; carry no :from-store tools. coreutils is the first package that is
;; entirely self-hosted: by the time it builds, sh, tar and make are all in the
;; store. Only two host programs remain in the store after that, the musl
;; cross compiler and the gzip that tar execs to decompress a source tarball.
;;
;; build! memoises per call rather than across calls, so the four are built one
;; after another. Each is already a dependency of the next, which is what fixes
;; the order.

(def toolchain
  (dependencies
   :build
   [sh make tar coreutils]))

(pprint toolchain)
(println "---------------------------")
(println "building the store's host tools")
(println "---------------------------")
(build sh)
(build make)
(build tar)
(build coreutils)
