# NixOS module: werwolf as a systemd service, with Caddy in front for TLS and
# the nixpkgs LiveKit module for voice. The same topology as
# deploy/docker-compose.yml, minus Docker.
#
# Minimal host config (lain):
#
#   services.werwolf = {
#     enable = true;
#     domain = "werwolf-chrz.duckdns.org";
#     tokenSecretFile = "/var/lib/secrets/werwolf/token-secret";
#     voice = {
#       publicIp = "78.47.48.43";
#       keyFile = "/var/lib/secrets/werwolf/livekit-keys";
#     };
#   };
#
# Secrets are files on the host, read through systemd credentials, so they never
# enter the nix store. Generate them once:
#
#   install -d -m 700 /var/lib/secrets/werwolf
#   openssl rand -base64 48 > /var/lib/secrets/werwolf/token-secret
#   echo "werwolf: $(openssl rand -hex 32)" > /var/lib/secrets/werwolf/livekit-keys
#   chmod 600 /var/lib/secrets/werwolf/*
{ config, lib, pkgs, ... }:

let
  cfg = config.services.werwolf;
  voice = cfg.voice;
  inherit (lib) mkOption mkEnableOption mkIf mkMerge types optional optionals;

  # The app wants the LiveKit pair as two env vars, LiveKit itself wants one
  # "key: secret" file. Splitting the file at start-up means there is exactly one
  # secret to generate and rotate, so the two can never drift apart — the failure
  # that looks like broken audio rather than a config error.
  start = pkgs.writeShellScript "werwolf-start" ''
    set -eu
    TOKEN_SECRET="$(< "$CREDENTIALS_DIRECTORY/token-secret")"
    export TOKEN_SECRET
    ${lib.optionalString voice.enable ''
      IFS= read -r pair < "$CREDENTIALS_DIRECTORY/livekit-keys"
      LIVEKIT_API_KEY="''${pair%%:*}"
      LIVEKIT_API_SECRET="''${pair#*:}"
      LIVEKIT_API_SECRET="''${LIVEKIT_API_SECRET#"''${LIVEKIT_API_SECRET%%[! ]*}"}"
      export LIVEKIT_API_KEY LIVEKIT_API_SECRET
    ''}
    exec ${lib.getExe cfg.package}
  '';
in
{
  options.services.werwolf = {
    enable = mkEnableOption "the werwolf game server";

    package = mkOption {
      type = types.package;
      description = "The werwolf package. The flake's nixosModules.werwolf sets this.";
    };

    commit = mkOption {
      type = types.nullOr types.str;
      default = null;
      example = "eb90632";
      description = ''
        The commit the package was built from, shown on the start page and in
        the log. The package itself carries none, so the same code is the same
        store path on every commit; the flake's nixosModules.werwolf sets this
        to its own revision.
      '';
    };

    domain = mkOption {
      type = types.str;
      example = "werwolf-chrz.duckdns.org";
      description = ''
        Public hostname. Caddy requests a Let's Encrypt certificate for it, so
        its DNS must already point at this host when the service first starts.
      '';
    };

    port = mkOption {
      type = types.port;
      # Not 3000, the app's own default: Grafana already holds 3000 on lain.
      default = 8340;
      description = "Loopback port the app listens on. Only Caddy talks to it.";
    };

    tokenSecretFile = mkOption {
      type = types.path;
      description = ''
        File holding the HS256 key for reconnect tokens. Forging a token means
        taking somebody else's seat, so keep it out of the nix store:
        a string path to a file on the host, not a nix path.
      '';
    };

    heap = mkOption {
      type = types.str;
      default = "320m";
      description = "JVM -Xmx. A few MB per room; 320m is generous for a friend group.";
    };

    memoryMax = mkOption {
      type = types.str;
      default = "640M";
      description = ''
        systemd MemoryMax for the app. Must leave room above the heap for the
        JVM's own overhead, or the kernel kills it mid-game instead of the JVM
        reporting an OutOfMemoryError.
      '';
    };

    settings = mkOption {
      type = types.attrsOf types.str;
      default = { };
      example = { DISCUSSION_SECS = "240"; FIRST_NIGHT_KILL = "false"; };
      description = ''
        Extra environment for the app: the optional knobs documented in
        deploy/.env.example. Nothing secret, since these land in the nix store.
      '';
    };

    caddy.enable = mkOption {
      type = types.bool;
      default = true;
      description = ''
        Serve the domain through Caddy with automatic HTTPS, proxying the app
        and LiveKit signalling. Turn off to wire up a different proxy yourself.
      '';
    };

    openFirewall = mkOption {
      type = types.bool;
      default = true;
      description = "Open 80/443, plus LiveKit's media ports when voice is on.";
    };

    voice = {
      enable = mkOption {
        type = types.bool;
        default = true;
        description = "Run a LiveKit SFU next to the app, so every player can talk.";
      };

      keyFile = mkOption {
        type = types.path;
        description = ''
          LiveKit key file, a single line `key: secret`. Shared by LiveKit and the
          app. A string path to a file on the host, not a nix path.
        '';
      };

      publicIp = mkOption {
        type = types.str;
        example = "78.47.48.43";
        description = ''
          The address LiveKit advertises in its ICE candidates. Pinned rather than
          discovered via STUN: a browser that cannot reach the advertised address
          connects signalling and then hears nothing.
        '';
      };

      tcpPort = mkOption {
        type = types.port;
        default = 7881;
        description = "Media over TCP, for players whose network blocks UDP.";
      };

      udpPortRange = {
        # A range rather than LiveKit's single muxed port, because the nixpkgs
        # module always sets a range and LiveKit ignores the mux while one is set.
        # Two connections per player: 200 ports covers any table this game seats.
        from = mkOption { type = types.port; default = 50000; };
        to = mkOption { type = types.port; default = 50199; };
      };
    };
  };

  config = mkIf cfg.enable (mkMerge [
    {
      systemd.services.werwolf = {
        description = "Werwolf game server";
        wantedBy = [ "multi-user.target" ];
        wants = [ "network-online.target" ];
        after = [ "network-online.target" ] ++ optional voice.enable "livekit.service";

        environment = {
          HOST = "127.0.0.1";
          PORT = toString cfg.port;
          DB_PATH = "/var/lib/werwolf/werwolf.db";
          JDK_JAVA_OPTIONS = "-Xmx${cfg.heap}";
          MEDIA_PROVIDER = if voice.enable then "livekit" else "noop";
        } // lib.optionalAttrs voice.enable {
          LIVEKIT_URL = "wss://${cfg.domain}/livekit";
        } // lib.optionalAttrs (cfg.commit != null) {
          WERWOLF_COMMIT = cfg.commit;
        } // cfg.settings;

        serviceConfig = {
          ExecStart = start;
          DynamicUser = true;
          # The event log. The only durable state: rooms are a fold over it.
          StateDirectory = "werwolf";
          StateDirectoryMode = "0700";
          LoadCredential = [ "token-secret:${cfg.tokenSecretFile}" ]
            ++ optional voice.enable "livekit-keys:${voice.keyFile}";

          MemoryMax = cfg.memoryMax;
          Restart = "on-failure";
          RestartSec = 5;

          # Hardening. Deliberately NOT MemoryDenyWriteExecute: the JIT needs it.
          NoNewPrivileges = true;
          PrivateTmp = true;
          PrivateDevices = true;
          ProtectSystem = "strict";
          ProtectHome = true;
          ProtectClock = true;
          ProtectHostname = true;
          ProtectKernelLogs = true;
          ProtectKernelModules = true;
          ProtectKernelTunables = true;
          ProtectControlGroups = true;
          RestrictAddressFamilies = [ "AF_INET" "AF_INET6" "AF_UNIX" ];
          RestrictNamespaces = true;
          RestrictRealtime = true;
          LockPersonality = true;
          SystemCallArchitectures = "native";
          UMask = "0077";
        };
      };
    }

    (mkIf voice.enable {
      services.livekit = {
        enable = true;
        inherit (voice) keyFile;
        # NOT the module's openFirewall: that also opens 7880, the signalling
        # port, which belongs behind Caddy and its certificate.
        openFirewall = false;
        settings = {
          port = 7880;
          rtc = {
            tcp_port = voice.tcpPort;
            port_range_start = voice.udpPortRange.from;
            port_range_end = voice.udpPortRange.to;
            use_external_ip = false;
            node_ip = voice.publicIp;
          };
        };
      };
    })

    (mkIf cfg.caddy.enable {
      services.caddy = {
        enable = true;
        virtualHosts.${cfg.domain}.extraConfig = ''
          encode zstd gzip

          ${lib.optionalString voice.enable ''
            # LiveKit signalling, prefix stripped. Media does not come through here.
            handle_path /livekit/* {
              reverse_proxy 127.0.0.1:7880 {
                flush_interval -1
              }
            }
          ''}

          handle {
            # flush_interval -1: never buffer, or WebSocket frames stall.
            reverse_proxy 127.0.0.1:${toString cfg.port} {
              flush_interval -1
              health_uri      /health
              health_interval 10s
              health_timeout  2s
            }
          }
        '';
      };
    })

    (mkIf cfg.openFirewall {
      networking.firewall = {
        allowedTCPPorts = optionals cfg.caddy.enable [ 80 443 ]
          ++ optional voice.enable voice.tcpPort;
        # 443/udp is HTTP/3, which Caddy enables by default.
        allowedUDPPorts = optional cfg.caddy.enable 443;
        allowedUDPPortRanges = optional voice.enable voice.udpPortRange;
      };
    })
  ]);
}
