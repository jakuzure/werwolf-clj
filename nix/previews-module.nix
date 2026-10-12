# NixOS module: a preview of every PR at https://pr-<n>.<domain>, run in the
# host's k3s as nix-snapshotter images.
#
# Minimal host config (lain), next to services.werwolf:
#
#   services.werwolf.previews = {
#     enable = true;
#     domain = "werwolf-chrz.duckdns.org";
#     githubTokenFile = "/var/lib/secrets/werwolf/github-token";
#     livekit.keyFile = "/var/lib/secrets/werwolf/livekit-keys";
#   };
#
# Secrets, once:
#   - github-token: a fine-grained PAT, read-only on this repo (pull requests,
#     commit statuses). It expires; renew it.
#   - livekit-keys: a second line `werwolf-preview: <secret>` in production's
#     key file. LiveKit reads every line, production's app only the first, and
#     only this line ever reaches k3s.
#       echo "werwolf-preview: $(openssl rand -hex 32)" >> /var/lib/secrets/werwolf/livekit-keys
{ config, lib, pkgs, ... }:

let
  cfg = config.services.werwolf.previews;
  inherit (lib) mkOption mkEnableOption mkIf types optional optionalAttrs;

  namespace = "werwolf-previews";
  kubeconfig = "/run/werwolf-previews-kubeconfig/kubeconfig";
  k3s = config.services.k3s.package;

  # The controller's rights: its own namespace, nothing else.
  rbac = [
    { apiVersion = "v1"; kind = "Namespace"; metadata.name = namespace; }
    {
      apiVersion = "v1";
      kind = "ServiceAccount";
      metadata = { name = "werwolf-previews"; inherit namespace; };
    }
    {
      apiVersion = "v1";
      kind = "Secret";
      type = "kubernetes.io/service-account-token";
      metadata = {
        name = "werwolf-previews-token";
        inherit namespace;
        annotations."kubernetes.io/service-account.name" = "werwolf-previews";
      };
    }
    {
      apiVersion = "rbac.authorization.k8s.io/v1";
      kind = "Role";
      metadata = { name = "werwolf-previews"; inherit namespace; };
      rules =
        let verbs = [ "get" "list" "watch" "create" "update" "patch" "delete" ];
        in [
          { apiGroups = [ "" ]; resources = [ "services" "secrets" "persistentvolumeclaims" ]; inherit verbs; }
          { apiGroups = [ "apps" ]; resources = [ "deployments" ]; inherit verbs; }
          { apiGroups = [ "metrics.k8s.io" ]; resources = [ "pods" ]; verbs = [ "get" "list" ]; }
        ];
    }
    {
      apiVersion = "rbac.authorization.k8s.io/v1";
      kind = "RoleBinding";
      metadata = { name = "werwolf-previews"; inherit namespace; };
      roleRef = { apiGroup = "rbac.authorization.k8s.io"; kind = "Role"; name = "werwolf-previews"; };
      subjects = [{ kind = "ServiceAccount"; name = "werwolf-previews"; inherit namespace; }];
    }
  ];

  # A kubeconfig for that ServiceAccount, written by root, handed to the
  # controller as a credential. k3s applies the manifests above on its own
  # schedule, so wait for the token to exist.
  writeKubeconfig = pkgs.writeShellScript "werwolf-previews-kubeconfig" ''
    set -eu
    kubectl="${k3s}/bin/k3s kubectl"
    for _ in $(seq 150); do
      token=$($kubectl -n ${namespace} get secret werwolf-previews-token \
                -o jsonpath='{.data.token}' 2>/dev/null | ${pkgs.coreutils}/bin/base64 -d || true)
      [ -n "$token" ] && break
      sleep 2
    done
    [ -n "$token" ] || { echo "no ServiceAccount token after 5 minutes" >&2; exit 1; }
    ca=$(${pkgs.coreutils}/bin/base64 -w0 /var/lib/rancher/k3s/server/tls/server-ca.crt)
    umask 077
    mkdir -p "$(dirname ${kubeconfig})"
    cat > ${kubeconfig}.tmp <<EOF
    apiVersion: v1
    kind: Config
    clusters:
      - name: k3s
        cluster: { server: "https://127.0.0.1:6443", certificate-authority-data: "$ca" }
    users:
      - name: werwolf-previews
        user: { token: "$token" }
    contexts:
      - name: werwolf-previews
        context: { cluster: k3s, user: werwolf-previews, namespace: ${namespace} }
    current-context: werwolf-previews
    EOF
    mv ${kubeconfig}.tmp ${kubeconfig}
  '';

  start = pkgs.writeShellScript "werwolf-previews-start" ''
    export KUBECONFIG="$CREDENTIALS_DIRECTORY/kubeconfig"
    export GITHUB_TOKEN_FILE="$CREDENTIALS_DIRECTORY/github-token"
    ${lib.optionalString (cfg.livekit.keyFile != null) ''
      export LIVEKIT_KEY_FILE="$CREDENTIALS_DIRECTORY/livekit-keys"
    ''}
    exec ${lib.getExe cfg.package}
  '';

  # pr-42.<domain> → label index of "pr-42" in Caddy's {labels.N}, counted
  # from the right.
  prLabel = toString (lib.length (lib.splitString "." cfg.domain));
in
{
  options.services.werwolf.previews = {
    enable = mkEnableOption "a preview of every werwolf PR in the host's k3s";

    package = mkOption {
      type = types.package;
      description = "The controller. The flake's nixosModules.werwolf-previews sets this.";
    };

    domain = mkOption {
      type = types.str;
      example = "werwolf-chrz.duckdns.org";
      description = ''
        Previews are served at pr-<n>.<domain>. Every subdomain must resolve to
        this host (DuckDNS does that by itself).
      '';
    };

    repo = mkOption {
      type = types.str;
      default = "jakuzure/werwolf-clj";
      description = "The GitHub repository whose PRs get previews.";
    };

    githubTokenFile = mkOption {
      type = types.path;
      description = "A read-only GitHub token. A string path to a file on the host.";
    };

    livekit = {
      keyFile = mkOption {
        type = types.nullOr types.path;
        default = null;
        description = ''
          LiveKit's key file. Only the line named `keyName` is read and handed
          to the previews. Null: previews are text-only.
        '';
      };
      keyName = mkOption {
        type = types.str;
        default = "werwolf-preview";
        description = "The previews' own key in that file. Never production's.";
      };
      url = mkOption {
        type = types.str;
        default = "wss://${cfg.domain}/livekit";
        defaultText = lib.literalExpression ''"wss://''${domain}/livekit"'';
        description = "Where previews' browsers reach LiveKit.";
      };
    };

    podMemory = mkOption {
      type = types.str;
      default = "384Mi";
      description = "Memory limit of one preview, and the size of a slot.";
    };

    memoryReserve = mkOption {
      type = types.str;
      default = "1Gi";
      description = "Memory left free for everything else on the host.";
    };

    maxPreviews = mkOption {
      type = types.ints.positive;
      default = 4;
      description = "Never more previews running than this, however much memory is free.";
    };

    interval = mkOption {
      type = types.ints.positive;
      default = 60;
      description = "Seconds between two rounds.";
    };

    askPort = mkOption {
      type = types.port;
      default = 8341;
      description = "Loopback port of the endpoint Caddy asks before issuing a certificate.";
    };

    # For the VM test.
    githubGraphqlUrl = mkOption {
      type = types.str;
      default = "https://api.github.com/graphql";
      internal = true;
    };
    meminfoPath = mkOption {
      type = types.str;
      default = "/proc/meminfo";
      internal = true;
    };
  };

  config = mkIf cfg.enable {
    services.k3s = {
      extraFlags = [ "--snapshotter=nix" ];
      # JSON written here rather than `content`, which renders YAML through
      # remarshal and so pulls in Python to build one small file.
      manifests."werwolf-previews.json".source = pkgs.writeText "werwolf-previews.json"
        (builtins.toJSON { apiVersion = "v1"; kind = "List"; items = rbac; });
    };
    # nix-snapshotter resolves images through the nix daemon.
    systemd.services.k3s.path = [ pkgs.nix ];

    systemd.services.werwolf-previews-kubeconfig = {
      description = "Kubeconfig for the werwolf PR preview controller";
      requires = [ "k3s.service" ];
      after = [ "k3s.service" ];
      serviceConfig = {
        Type = "oneshot";
        RemainAfterExit = true;
        ExecStart = writeKubeconfig;
      };
    };

    systemd.services.werwolf-previews = {
      description = "werwolf PR previews";
      wantedBy = [ "multi-user.target" ];
      requires = [ "werwolf-previews-kubeconfig.service" ];
      after = [ "werwolf-previews-kubeconfig.service" "network-online.target" ];
      wants = [ "network-online.target" ];

      environment = {
        PREVIEWS_REPO = cfg.repo;
        PREVIEWS_DOMAIN = cfg.domain;
        PREVIEWS_NAMESPACE = namespace;
        GITHUB_GRAPHQL_URL = cfg.githubGraphqlUrl;
        POD_MEMORY = cfg.podMemory;
        MEMORY_RESERVE = cfg.memoryReserve;
        MAX_PREVIEWS = toString cfg.maxPreviews;
        INTERVAL_SECS = toString cfg.interval;
        ASK_PORT = toString cfg.askPort;
        MEMINFO_PATH = cfg.meminfoPath;
        LIVEKIT_KEY_NAME = cfg.livekit.keyName;
        LIVEKIT_URL = cfg.livekit.url;
        # kubectl's discovery cache
        HOME = "/var/cache/werwolf-previews";
      };

      serviceConfig = {
        ExecStart = start;
        DynamicUser = true;
        CacheDirectory = "werwolf-previews";
        LoadCredential = [
          "kubeconfig:${kubeconfig}"
          "github-token:${cfg.githubTokenFile}"
        ] ++ optional (cfg.livekit.keyFile != null) "livekit-keys:${cfg.livekit.keyFile}";
        Restart = "always";
        RestartSec = 10;

        NoNewPrivileges = true;
        PrivateTmp = true;
        PrivateDevices = true;
        ProtectSystem = "strict";
        ProtectHome = true;
        ProtectKernelTunables = true;
        ProtectKernelModules = true;
        ProtectControlGroups = true;
        RestrictAddressFamilies = [ "AF_INET" "AF_INET6" "AF_UNIX" ];
        RestrictNamespaces = true;
        LockPersonality = true;
        UMask = "0077";
      };
    };

    services.caddy = {
      enable = true;
      # Certificates only for hosts the controller says are previews.
      globalConfig = ''
        on_demand_tls {
          ask http://127.0.0.1:${toString cfg.askPort}/ask
        }
      '';
      # A host on a k3s node reaches ClusterIPs and CoreDNS directly, so each
      # preview's Service is found by name and no port table is needed. A
      # sleeping preview has no endpoints: that is the 502.
      virtualHosts."*.${cfg.domain}".extraConfig = ''
        tls {
          on_demand
        }
        reverse_proxy {
          # k3s CoreDNS resolves the Service names
          dynamic a {
            name {labels.${prLabel}}.${namespace}.svc.cluster.local
            port 8080
            resolvers 10.43.0.10
          }
          # never buffer, or WebSocket frames stall
          flush_interval -1
        }
        handle_errors 502 503 {
          respond "This preview is asleep: lain is short on memory. It wakes up by itself when there is room again." 503
        }
      '';
    };
  };
}
