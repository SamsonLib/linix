(defn gnu-build
  [& {:keys [configure-flags phases environment dependency-flags]
      :or {configure-flags []
           phases {}
           environment {}}}]
  (let [build
        {:type :build-system
         :name :gnu

         :environment
         environment

         :phases
         (merge
          {:unpack (shell-phase "tar -xvf $src")

           :configure
           (shell-phase
            (str
             "./configure --prefix=$out"
             (when (seq configure-flags)
               (str " " (join " " configure-flags)))))

           ;; make takes SHELL from the makefile or the command line, never
           ;; from the environment, so it has to be named here for recipes to
           ;; run under the shell the store built rather than the host's. The
           ;; core puts that shell's path in $SHELL before the phases run.
           :build (shell-phase "make SHELL=$SHELL -j12")

           :install (shell-phase "make install")}

          phases)}]
    ;; :dependency-flags is only recorded when a package opts out, so the
    ;; usual case stays quiet. This file is read by the interpreter, whose
    ;; builtin set is small, hence merge and = rather than assoc and false?.
    (merge build
           (if (= dependency-flags false)
             {:dependency-flags false}
             {}))))

