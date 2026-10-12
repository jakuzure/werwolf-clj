# The PR previews end to end, in a VM: real k3s with nix-snapshotter, the
# controller with its RBAC, Caddy, and the preview image. Only GitHub is fake:
# a tiny server answering the controller's GraphQL query from a file the test
# rewrites, and /proc/meminfo is a file the test controls too.
#
#   nix build -L .#checks.x86_64-linux.previews
#
# Also the guard for nixpkgs updates: if a k3s or nix-snapshotter bump breaks
# nix images, this fails before lain does.
{ pkgs, previewsModule, previewImage }:

let
  domain = "previews.test";
  imageRef = "nix:0${previewImage}";

  fakeGithub = pkgs.writeShellScript "fake-github" ''
    exec ${pkgs.babashka}/bin/bb -e '
      (require (quote [org.httpkit.server :as s]))
      (s/run-server (fn [_] {:status 200
                             :headers {"Content-Type" "application/json"}
                             :body (slurp "/run/fake-github.json")})
                    {:ip "127.0.0.1" :port 9999})
      (deref (promise))'
  '';

  pr = n: ''
    {"number": ${toString n}, "headRefOid": "sha${toString n}", "isCrossRepository": false,
     "commits": {"nodes": [{"commit": {"status": {"context": {
       "state": "SUCCESS", "description": "${imageRef}",
       "createdAt": "2026-10-12T10:00:0${toString n}Z"}}}}]}}
  '';
  github = prs: pkgs.writeText "github.json" ''
    {"data": {"repository": {"pullRequests": {"pageInfo": {"hasNextPage": false},
      "nodes": [${pkgs.lib.concatStringsSep "," prs}]}}}}
  '';
  onePr = github [ (pr 1) ];
  noPrs = github [ ];
  githubDown = pkgs.writeText "github-down.json" ''{"errors": [{"message": "boom"}]}'';

  meminfo = mib: pkgs.writeText "meminfo-${toString mib}" ''
    MemTotal:        4000000 kB
    MemAvailable:    ${toString (mib * 1024)} kB
  '';
in
pkgs.testers.runNixOSTest {
  name = "werwolf-previews";

  nodes.machine = { config, ... }: {
    imports = [ previewsModule ];

    virtualisation = {
      cores = 2;
      memorySize = 3800;
      diskSize = 8192;
      # no network in the VM: the image's closure is registered here instead
      # of being substituted from Cachix
      additionalPaths = [ previewImage ];
    };

    environment.systemPackages = [ pkgs.curl ];

    services.k3s = {
      enable = true;
      role = "server";
      images = [ config.services.k3s.package.airgap-images-amd64-tar-zst ];
      extraFlags = [ "--disable=traefik" ];
    };

    services.werwolf.previews = {
      enable = true;
      inherit domain;
      githubTokenFile = "${pkgs.writeText "token" "fake"}";
      githubGraphqlUrl = "http://127.0.0.1:9999/graphql";
      meminfoPath = "/run/fake-meminfo";
      interval = 2;
    };
    # no ACME in a VM: Caddy's own CA, still issued on demand
    services.caddy.globalConfig = "local_certs";

    systemd.services.fake-github = {
      wantedBy = [ "multi-user.target" ];
      before = [ "werwolf-previews.service" ];
      serviceConfig.ExecStart = fakeGithub;
    };
    systemd.tmpfiles.rules = [
      "C /run/fake-github.json 0644 root root - ${onePr}"
      "C /run/fake-meminfo 0644 root root - ${meminfo 8192}"
    ];
  };

  testScript = ''
    kubectl = "k3s kubectl -n werwolf-previews"
    curl = "curl -sk --resolve pr-1.${domain}:443:127.0.0.1"

    def replicas():
        return machine.succeed(f"{kubectl} get deployment pr-1 -o jsonpath='{{.spec.replicas}}'").strip()

    def logs():
        print(machine.execute("journalctl -u werwolf-previews --no-pager | tail -20")[1])
        print(machine.execute(f"{kubectl} get all,pvc,secret")[1])
        print(machine.execute(f"{kubectl} describe pod -l werwolf-previews/pr=1 | tail -25")[1])

    start_all()

    with subtest("the real /proc/meminfo can be read (the test uses a file)"):
        out = machine.succeed("${pkgs.babashka-unwrapped}/bin/bb --classpath ${../previews/src} -e "
                              "\"(require 'werwolf.previews.main 'werwolf.previews.k8s) "
                              "(werwolf.previews.k8s/mem-available (werwolf.previews.main/read-proc \\\"/proc/meminfo\\\"))\"")
        assert int(out) > 0, out

    with subtest("a PR with a preview status gets a running preview"):
        machine.wait_for_unit("k3s.service")
        machine.wait_until_succeeds("k3s kubectl get node machine | grep -w Ready", timeout=300)
        machine.wait_until_succeeds("k3s kubectl -n kube-system get pod -l k8s-app=kube-dns | grep '1/1 *Running'", timeout=300)
        machine.wait_for_unit("werwolf-previews.service")
        try:
            machine.wait_until_succeeds(f"{kubectl} get pod -l werwolf-previews/pr=1 | grep '1/1 *Running'", timeout=300)
        finally:
            logs()

    with subtest("Caddy serves it, with a certificate only for real previews"):
        machine.succeed("curl -sf 'http://127.0.0.1:8341/ask?domain=pr-1.${domain}'")
        machine.fail("curl -sf 'http://127.0.0.1:8341/ask?domain=pr-2.${domain}'")
        out = machine.wait_until_succeeds(f"{curl} -f https://pr-1.${domain}/health", timeout=120)
        assert "ok" in out, out

    with subtest("short on memory: asleep after the grace rounds, its data kept"):
        machine.succeed("cp ${meminfo 100} /run/fake-meminfo")
        machine.wait_until_succeeds(f"[ \"$({kubectl} get deployment pr-1 -o jsonpath='{{.spec.replicas}}')\" = 0 ]", timeout=60)
        machine.succeed(f"{kubectl} get pvc pr-1")
        machine.wait_until_succeeds(f"{curl} https://pr-1.${domain}/ | grep asleep", timeout=60)

    with subtest("memory back: it wakes up by itself"):
        machine.succeed("cp ${meminfo 8192} /run/fake-meminfo")
        machine.wait_until_succeeds(f"{curl} -f https://pr-1.${domain}/health", timeout=180)

    with subtest("GitHub failing changes nothing"):
        machine.succeed("cp ${githubDown} /run/fake-github.json")
        machine.sleep(10)
        assert replicas() == "1", replicas()
        machine.succeed(f"{kubectl} get pvc pr-1")

    with subtest("a closed PR loses everything, its database included"):
        machine.succeed("cp ${noPrs} /run/fake-github.json")
        machine.wait_until_succeeds(f"[ -z \"$({kubectl} get deployment,service,secret,pvc -l werwolf-previews/pr=1 -o name)\" ]", timeout=60)
        machine.fail("curl -sf 'http://127.0.0.1:8341/ask?domain=pr-1.${domain}'")

    with subtest("its image and the GC root on its closure go with the next prune"):
        roots = "/var/lib/rancher/k3s/agent/containerd/io.containerd.snapshotter.v1.nix/gcroots"
        machine.succeed("k3s crictl images | grep nix-image-werwolf")
        machine.succeed(f"ls {roots}/*/ | grep closure-info")
        machine.wait_until_succeeds("! k3s crictl ps -a | grep -w werwolf", timeout=60)
        machine.succeed("systemctl start werwolf-previews-prune-images")
        machine.fail("k3s crictl images | grep nix-image-werwolf")
        # containerd's GC drops the snapshot, and nix-snapshotter its root, async
        machine.wait_until_succeeds(f"! ls {roots}/*/ 2>/dev/null | grep closure-info", timeout=60)
  '';
}
