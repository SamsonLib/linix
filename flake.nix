{
  # inputs.self.submodules = true;
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    jolt.url = "git+https://github.com/jolt-lang/jolt.git?submodules=1";
  };

  outputs =
    { self, nixpkgs, ... }@inputs:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config.allowUnfree = true;
      };
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          pkgs.clojure
          pkgs.cljfmt
          pkgs.clj-kondo
          pkgs.clojure-lsp
          # A libc-less musl cross compiler, so a package can be linked against
          # the musl this store builds rather than the host's glibc.
          pkgs.pkgsCross.musl64.stdenv.cc
          inputs.jolt.packages.${system}.default
        ];
      };
    };
}
