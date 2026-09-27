;; app half — the runtime half is compiled separately (runtime.ss)
(jolt-startup-profile-mark! "app image begin")

;; === native libraries (required) ===
(jolt-startup-profile-mark! "required native libraries")

;; === embedded resources ===
(set-source-roots!* (list "./src" "jolt-core" "stdlib" "vendor/fs/src" "vendor/process/src" "vendor/cli/src" "vendor/grenadine/src" "vendor/grenadine-generated" ))
(jolt-startup-profile-mark! "embedded resources and source roots")

;; === app namespace pre-registration ===
(intern-ns! "builder")
(intern-ns! "errors")
(intern-ns! "reader")
(intern-ns! "modules")
(intern-ns! "params")
(intern-ns! "user-function")
(intern-ns! "interpreter")
(intern-ns! "core")
(jolt-startup-profile-mark! "app namespace registration")

;; === app (declarations; the bodies run at scheme-start) ===
