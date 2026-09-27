(ns core
  (:require [builder :as builder]
            [errors :as errors]
            [interpreter :as interp]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File InputStream OutputStream RandomAccessFile Writer]
           [java.net HttpURLConnection URL]
           [java.security MessageDigest]))

;; The package tree the DSL in test.clj builds. A package is a map of
;; {:type :package :name :version :source :dependencies :host-tools :build
;; :meta}; :build is a build-system map of {:name :environment :phases}, and
;; each phase is {:type :shell :command} or {:type :exec :command}.
;;
;; Building one means: fetch :source into the store's downloads directory,
;; hand it to the phases in order with $src and $out set, and let them install
;; into <store>/<name>-<version>. Dependencies are built first and their
;; output directories go on the phases' PATH, so a package is compiled and
;; linked against what it declared.

(def default-store-dir
  "Where built packages land, relative to the working directory."
  "./store")

(def ^:private built-marker
  "Written into a package's output directory once every phase has succeeded,
   so a later run can skip what the store already holds."
  ".clojv3-built")

(def ^:private log-dir-name
  "Build logs, kept next to the sources a package was built from."
  ".clojv3-logs")

;; Fetching

(defn- absolute
  "The File for `f` with an absolute path. Phases run in the package's own
   directory, so $src and $out cannot be relative to where the build started."
  ^File [f]
  (.getAbsoluteFile (io/file f)))

(defn- env-key
  "Environment variable names come from the DSL as keywords or strings."
  [k]
  (if (keyword? k) (name k) (str k)))

(def ^:private url-timeout-ms 30000)

(def ^:private max-redirects 10)

(defn- sha256-hex
  "Lowercase hex SHA-256 of a file."
  ^String [^File f]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream f)]
      (let [buf (byte-array 65536)]
        (loop []
          (let [n (.read in buf)]
            (when (pos? n)
              (.update digest buf 0 n)
              (recur))))))
    (->> (.digest digest)
         (map #(format "%02x" (bit-and % 0xff)))
         (apply str))))

(defn- expected-sha256
  "The hex digest named by a \"sha256-<hex>\" hash string, or nil when the
   hash is missing or names an algorithm we do not verify."
  [^String hash]
  (when-not (str/blank? hash)
    (second (re-matches #"(?i)sha256-(.+)" hash))))

(defn- open-url
  "Connect to `url` and return the connection positioned on a 2xx response.

   Redirects are followed by hand: HttpURLConnection refuses to hop between
   http and https, and archive hosts rely on that."
  ^HttpURLConnection [^String url ^long hops]
  (let [conn (doto ^HttpURLConnection (.openConnection (URL. url))
               (.setRequestProperty "User-Agent" "clojv3")
               (.setInstanceFollowRedirects false)
               (.setConnectTimeout url-timeout-ms)
               (.setReadTimeout url-timeout-ms))
        status (.getResponseCode conn)]
    (cond
      (>= status 400)
      (do (.disconnect conn)
          (errors/fail :fetch-failed
                       (str "Could not fetch " url " — server said HTTP " status)
                       {:url url :status status}))

      (<= 300 status 399)
      (let [location (.getHeaderField conn "Location")]
        (.disconnect conn)
        (when (or (str/blank? location) (>= hops max-redirects))
          (errors/fail :fetch-failed
                       (str "Too many redirects while fetching " url)
                       {:url url}))
        (recur (str (URL. (URL. url) location)) (inc hops)))

      :else conn)))

(defn- download!
  "Stream `url` into `f`. The bytes go to a sibling .part file first, so an
   interrupted download is never mistaken for a finished one."
  ^File [^File f ^String url]
  (let [part (io/file (str f ".part"))]
    (println (str "[clojv3] fetching " url))
    (let [conn (open-url url 0)]
      (try
        (with-open [in  (.getInputStream conn)
                    out (io/output-stream part)]
          (io/copy in out))
        (finally (.disconnect conn))))
    (when-not (.renameTo part f)
      (.delete part)
      (errors/fail :fetch-failed
                   (str "Could not write " (.getPath f))
                   {:url url}))
    f))

(defn- check-hash!
  "Delete `f` and fail when its SHA-256 is not `expected`, so the next run
   fetches the source again instead of reusing a bad download."
  [^File f ^String url ^String expected]
  (when expected
    (let [actual (sha256-hex f)]
      (when-not (= expected (str/lower-case actual))
        (.delete f)
        (errors/fail :hash-mismatch
                     (str "Hash mismatch for " url "\n"
                          "  expected sha256-" expected "\n"
                          "  actual   sha256-" actual)
                     {:url url :expected expected :actual actual})))))

(defn- fetch-url!
  "Downloads a {:type :fetch :method :url :name :url :hash} source into
   <store-dir>/downloads/<name> and returns that File. The hash is verified
   whether the file was just downloaded or was already in the store."
  ^File [store-dir {:keys [name url hash]}]
  (when-not (and (seq name) (seq url))
    (errors/fail :unsupported-source
                 (str "A :fetch source needs a :name and a :url — got "
                      (pr-str {:name name :url url}))
                 {:name name :url url}))
  (let [dl-dir (absolute (io/file store-dir "downloads"))
        dest   (io/file dl-dir name)]
    (.mkdirs dl-dir)
    (when-not (.exists dest)
      (download! dest url))
    (check-hash! dest url (expected-sha256 hash))
    dest))

(defn- fetch-source!
  "Downloads a package's source and returns the File, or nil for a package
   that builds something already on disk."
  ^File [store-dir source]
  (case (:type source)
    :fetch (fetch-url! store-dir source)
    nil    nil
    (errors/fail :unsupported-source
                 (str "Don't know how to fetch a " (pr-str (:type source))
                      " source: " (pr-str source))
                 {:source source})))

;; Host tools
;;
;; A package declares in :host-tools the binaries its phases expect to run.
;; Each is looked for in the store first -- a dependency's bin directory -- and
;; only then on the host's own PATH, so a tool this store has built for itself
;; always wins over the host's copy. :from-store names the ones that must come
;; out of the store, which is what catches a package that declares a tool
;; without taking the dependency that supplies it.
;;
;; sh, tar and make are circular: each is needed to build the others, so the
;; first links in that chain declare host tools only and build against the
;; host's copies. That is the bootstrap this store starts from, and it is why
;; the first three packages here carry no :from-store.

(defn- store-bin-dirs
  "The bin directory of each dependency output."
  [dep-outputs]
  (mapv #(str (io/file % "bin")) dep-outputs))

(defn- first-executable
  "The first `binary` found in `dirs`, as a path, or nil when none of them has
   it."
  [dirs binary]
  (->> dirs
       (map #(io/file % binary))
       (filter #(.isFile ^File %))
       (mapv #(.getPath ^File %))
       first))

(defn- host-path-dirs
  "The directories on the host's PATH, the same lookup a phase would get."
  []
  (remove str/blank?
          (str/split (or (System/getenv "PATH") "")
                     (re-pattern File/pathSeparator))))

(defn- find-tool
  "Where `binary` comes from: the store, or else the host's PATH. Returns
   {:path p :source :store|:host}, or nil when it is nowhere to be found."
  [dep-outputs binary]
  (or (when-let [p (first-executable (store-bin-dirs dep-outputs) binary)]
        {:path p :source :store})
      (when-let [p (first-executable (host-path-dirs) binary)]
        {:path p :source :host})))

(defn- check-host-tools!
  "Holds a package to the :host-tools it declared, before any phase runs.

   A tool that cannot be found is reported here rather than left to fail inside
   a phase as a bare command-not-found: the declaration is the package author's
   statement of what the build needs, so it is the place to hold them to it. A
   :from-store tool that resolved to the host is the more interesting failure,
   because the build would otherwise succeed against a different shell or make
   than the one the store provides, and only show up as a difference later.

   Takes the package, since the declaration is under its :host-tools."
  [{:keys [name host-tools]} dep-outputs]
  (let [{:keys [binaries from-store]} host-tools
        tools    (into {} (map (fn [b] [b (find-tool dep-outputs b)])) binaries)]
    (doseq [b binaries
            :when  (not (get tools b))]
      (errors/fail :missing-host-tool
                   (str name " declares " b " as a host tool, but it is in "
                        "neither the store nor the host's PATH")
                   {:package name :tool b :binaries binaries}))
    (doseq [b from-store
            :when  (not (some #{b} binaries))]
      (errors/fail :undeclared-host-tool
                   (str name " wants " b " from the store but does not declare "
                        "it in :binaries, so it is never looked up")
                   {:package name :tool b :from-store from-store}))
    (doseq [b from-store
            :let  [{:keys [path source]} (get tools b)]
            :when  (not= :store source)]
      (errors/fail :host-tool-not-from-store
                   (str name " wants " b " from the store, but it resolves to "
                        (pr-str path) " -- add the package that provides it to "
                        ":dependencies")
                   {:package name :tool b :path path}))))

(defn- build-shell
  "The shell that runs a :shell phase, and that make runs its recipes under.

   A dependency that provides sh wins over the host's, so from the first link
   in the chain onwards every phase runs under the shell this store built
   rather than the host's. It is always an absolute path: a phase's own PATH
   can differ from the one this lookup used, and a name like \"sh\" passed to
   make would then be resolved by make rather than by us."
  [dep-outputs]
  (or (:path (find-tool dep-outputs "sh")) "sh"))

;; Phases

(def ^:private phase-order
  "The phases every build system is expected to run, in that order. A phase
   outside this list runs after these, in the order it was declared."
  [:unpack :patch :configure :build :check :install])

(defn- phase-seq
  "The keys of `phases` in execution order."
  [phases]
  (let [known-count (count phase-order)]
    (->> (map-indexed vector (keys phases))
         (sort-by (fn [[declared phase]]
                    (let [known (.indexOf ^java.util.List phase-order phase)]
                      [(if (neg? known) known-count known) declared])))
         (mapv second))))

(defn- phase-command
  "The argv for a phase: :shell phases go through `shell`, :exec phases run
   their own argv."
  [shell {:keys [type command] :as phase}]
  (case type
    :shell [shell "-c" command]
    :exec  (vec command)
    (errors/fail :unknown-phase
                 (str "Don't know how to run a " (pr-str type) " phase: "
                      (pr-str phase))
                 {:phase phase})))

(defn- log-tail
  "The last `limit` bytes of `f` as a string, for phase failure messages."
  ^String [^File f ^long limit]
  (with-open [raf (RandomAccessFile. f "r")]
    (let [len   (.length raf)
          start (max 0 (- len limit))
          buf   (byte-array (- len start))]
      (.seek raf start)
      (.readFully raf buf)
      (String. buf "UTF-8"))))

(defn- copy-stream!
  "Copy `in` to stdout as it arrives, so a long build streams instead of
   filling memory, and into `log`, so the full output survives for later."
  [^InputStream in ^File log]
  (with-open [out ^OutputStream (io/output-stream log)]
    (let [buf (byte-array 65536)]
      (loop []
        (let [n (.read in buf)]
          (when-not (neg? n)
            (.write ^Writer *out* (String. buf 0 n "UTF-8"))
            (.flush *out*)
            (.write out buf 0 n)
            (recur)))))))

(defn- run-phase!
  "Runs the build phase `label` in `cwd` with `env`, writing its output to
   <log-dir>/<label>.log. Fails with the tail of that log when the phase
   exits non-zero."
  [label {:keys [command] :as phase} {:keys [cwd env log-dir build-shell]}]
  (when (seq command)
    (let [argv (phase-command build-shell phase)
          log  (io/file log-dir (str (name label) ".log"))
          pb   (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String argv))]
      (.mkdirs log-dir)
      (.directory pb (io/file cwd))
      ;; The phases inherit this process's environment and have the build
      ;; variables layered on top: inside a nix shell every tool is a wrapper
      ;; script that needs its NIX_* variables, so clearing the environment
      ;; would leave the phases with no working compiler.
      (let [pb-env (.environment pb)]
        (doseq [[k v] env :when v]
          (.put pb-env (env-key k) (str v))))
      (.redirectErrorStream pb true)
      (println (str "[clojv3] " (name label) ": " (pr-str (vec argv))))
      (let [proc (.start pb)
            code (with-open [in (.getInputStream proc)]
                   (copy-stream! in log)
                   (.waitFor proc))]
        (when-not (zero? code)
          (let [tail (str/trim (log-tail log 8192))]
            (errors/fail :phase-failed
                         (str "Phase " (name label) " in " (.getPath ^File cwd)
                              " failed with exit code " code
                              (when-not (str/blank? tail)
                                (str "\n--- last output of " (name label) " ---\n"
                                     tail)))
                         {:phase (vec argv)
                          :exit  code
                          :cwd   (.getPath ^File cwd)
                          :log   (.getPath log)})))
        command))))

;; Packages

(defn store-path
  "Where a package's output lives inside the local store,
   e.g. ./store/tree-2.3.2"
  ^File [store-dir name version]
  (io/file store-dir (str name "-" version)))

(defn- dep-dirs
  "The `dir` subdirectories of `dep-outputs` that exist, as paths. A dependency
   that installs only a program has neither an include nor a lib directory."
  [dep-outputs dir]
  (->> dep-outputs
       (map #(io/file % dir))
       (filter #(.isDirectory ^File %))
       (mapv #(.getPath ^File %))))

(defn- append-flags
  "Adds `flags` to the environment variable `var`, after whatever the package
   declared for itself. Depending on something supplements a build's flags
   rather than replacing them."
  [env var flags]
  (if (str/blank? flags)
    env
    (update env var #(str/join " " (remove str/blank? [% flags])))))

(defn- link-flags
  "The linker flags that build against one dependency's lib directory: -B so
   the linker finds the C runtime objects it installed, -L for its libraries,
   and an rpath so the result can still resolve them once it has been moved
   out of the store. A dependency that ships a libc is also made the dynamic
   loader, since a host compiler wrapper otherwise names its own libc and the
   dependency would be linked against in name only."
  ^String [^String lib]
  (let [libc (io/file lib "libc.so")]
    (str "-B" lib
         " -L" lib
         " -Wl,-rpath," lib
         (when (.exists libc) (str " -Wl,-dynamic-linker," (.getPath libc))))))

(defn- package-env
  "The environment a package's phases run in. Its dependencies' bin
   directories come first, so a package is built against what it declared,
   then the host's own PATH, which is where its host tools come from.

   Depending on a package also means seeing what it installed: its headers go
   on the include path, and its libraries on the link path along with an rpath,
   so a binary that links against the store can still resolve them at run time.
   -B points the linker at the dependency's own C runtime objects, which is
   what lets a dependency such as musl supply the start files and the loader.

   A build system can set :dependency-flags to false to keep its dependencies
   off the include and link paths and see only their binaries. A package that
   ships a libc has to, until it also names a compiler that targets it: the
   flags would otherwise point a host compiler at a libc it is not building
   against.

   SHELL is exported as the same shell the phases run under, so a build system
   can hand it to make on the command line. Make takes SHELL from the makefile
   or the command line but never from the environment, so a build that wants
   its recipes to run under the store's shell has to say so explicitly."
  [{:keys [name version src out environment dependency-flags build-shell]}
   dep-outputs]
  (let [includes (when (not (false? dependency-flags))
                   (str/join " " (map #(str "-I" %)
                                      (dep-dirs dep-outputs "include"))))
        links    (when (not (false? dependency-flags))
                   (str/join " " (map link-flags (dep-dirs dep-outputs "lib"))))
        env      (merge
                  {"out"     (str out)
                   "name"    (str name)
                   "version" (str version)
                   "SHELL"   build-shell
                   "PATH"    (str/join File/pathSeparator
                                       (concat (store-bin-dirs dep-outputs)
                                               [(System/getenv "PATH")]))}
                  (when src {"src" (str src)})
                  (into {} (map (fn [[k v]] [(env-key k) (str v)])) environment))]
    (-> env
        (append-flags "CPPFLAGS" includes)
        (append-flags "LDFLAGS" links))))

(defn- already-built?
  [^File out]
  (.exists (io/file out built-marker)))

(defn- source-root
  "Where the phases after :unpack run. Archive tarballs wrap everything in a
   single top-level directory, so that directory becomes the working
   directory. A build system can name its own with :source-root, or set it to
   false to stay in the package's own directory."
  ^File [build ^File work]
  (let [declared (:source-root build)
        unpacked (filter (fn [^File f]
                           (and (.isDirectory f)
                                (not= log-dir-name (.getName f))))
                         (.listFiles work))]
    (cond
      (string? declared) (io/file work declared)
      (false? declared)  work
      (= 1 (count unpacked)) (first unpacked)
      :else work)))

(defn build-package!
  "Builds one package: fetches its source, runs its build-system phases in a
   scratch directory with $src and $out set, and lets them install into
   <store-dir>/<name>-<version>. `dep-outputs` are the store paths of the
   packages it depends on, which go on the phases' PATH. Returns the output
   path."
  ^String [store-dir {:keys [name version source build] :as pkg} dep-outputs]
  (when-not (and (seq name) (seq version))
    (errors/fail :not-a-package
                 (str "A package needs a :name and a :version — got "
                      (pr-str (select-keys pkg [:name :version])))
                 {:package (pr-str pkg)}))
  (let [out  (absolute (store-path store-dir name version))
        work (absolute (io/file store-dir "tmp" (str name "-" version)))
        logs (io/file work log-dir-name)]
    (if (already-built? out)
      (do (println (str "[clojv3] " name "-" version ": already in the store"))
          (.getPath out))
      (do
        (println (str "[clojv3] building " name "-" version))
        (check-host-tools! pkg dep-outputs)
        (.mkdirs out)
        (.mkdirs work)
        (let [src (fetch-source! store-dir source)
              build-shell (build-shell dep-outputs)
              env (package-env {:name             name
                                :version          version
                                :src              src
                                :out              out
                                :environment      (:environment build)
                                :build-shell      build-shell
                                :dependency-flags (:dependency-flags build)}
                               dep-outputs)
              phases (:phases build)]
          (doseq [label (phase-seq phases)]
            (when-let [body (get phases label)]
              ;; Everything after :unpack runs in the unpacked source tree.
              (run-phase! label body
                          {:cwd         (if (= :unpack label) work
                                            (source-root build work))
                           :env         env
                           :log-dir     logs
                           :build-shell build-shell})))
          (spit (io/file out built-marker)
                (str (str/join "\n" (map #(clojure.core/name %)
                                         (phase-seq phases)))
                     "\n"))
          (.getPath out))))))

(defn- package-id
  [pkg]
  [(:name pkg) (:version pkg)])

(defn- package-deps
  "The packages that have to exist before `pkg` can be built: its build
   dependencies first, then the runtime ones it links against."
  [pkg]
  (let [{:keys [build runtime]} (:dependencies pkg)]
    (concat (vec build) (vec runtime))))

(defn- build-one!
  [store-dir state pkg]
  (let [id (package-id pkg)]
    (or (get-in @state [:outputs id])
        (do
          (when (contains? (:building @state) id)
            (errors/fail :dependency-cycle
                         (str "Dependency cycle: " (pr-str id)
                              " needs itself to be built first")
                         {:package id}))
          (swap! state update :building conj id)
          (let [dep-outputs (mapv #(build-one! store-dir state %)
                                  (package-deps pkg))
                out (build-package! store-dir pkg dep-outputs)]
            (swap! state (fn [s] (-> s
                                     (update :building disj id)
                                     (assoc-in [:outputs id] out))))
            out)))))

(defn build!
  "Builds `pkg` and everything it depends on into `store-dir`, each package
   once and in dependency order. Returns the store path of `pkg`."
  ^String [store-dir pkg]
  (let [state (atom {:outputs {} :building #{}})]
    (build-one! store-dir state pkg)))

;; Running interpreted files

(defn default-context
  ([] (default-context default-store-dir))
  ([store-dir]
   (-> (builder/empty-builder)
       builder/with-standard-library
       (builder/with-module-ext "clj")
       (assoc :store-dir store-dir)
       ;; (build tree) has to really build tree, not assemble a context.
       (builder/with-builtin 'build (fn [pkg] (build! store-dir pkg)))
       (builder/with-builtin 'square (fn [x] (* x x)))
       (builder/with-builtin 'hello (fn [name] (println "Hello from the interpreter," name)))
       builder/build)))

(defn -main
  [& [file store-dir]]
  (try
    (when-not file
      (errors/fail :bad-usage
                   "Expected a file to run.\nUsage: jolt -m core <file> [store-dir]"
                   {:args (vec *command-line-args*)}))
    (interp/eval-file (default-context (or store-dir default-store-dir)) file)
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*] (println (ex-message e)))
      (System/exit 1))))
