# The PR preview controller (previews/): a babashka program, run from its
# sources. kubectl comes along; which cluster it talks to is KUBECONFIG's
# business, set by nix/previews-module.nix.
#
# babashka-unwrapped: nixpkgs' `babashka` wraps it with the Clojure CLI for
# deps.edn support and a JDK to run that on, neither of which this needs.
{ writeShellApplication, babashka-unwrapped, kubectl }:

writeShellApplication {
  name = "werwolf-previews";
  runtimeInputs = [ babashka-unwrapped kubectl ];
  text = ''
    exec bb --classpath ${../previews/src} --main werwolf.previews.main "$@"
  '';
}
