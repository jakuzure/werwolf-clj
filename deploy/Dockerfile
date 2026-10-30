# Runtime-only image: it expects the uberjar to ALREADY exist in target/.
#
# That is deliberate. Building the frontend inside the container would mean
# shipping Node, npm, the JDK and a Maven cache into a builder stage to produce
# a 26 MB jar — a slow, fat build whose only gain is not needing a toolchain on
# the machine that runs `docker build`. This project's build already lives in
# bb.edn, and `bb docker` runs `bb uberjar` first (which compiles the release
# CLJS and the minified CSS into resources/public before the jar is packed).
#
# So: build with `bb docker`, not with a bare `docker build`. A bare one fails
# on the COPY below with "no source files were specified", which is the correct
# failure — it means the artifact you were about to ship does not exist.

# 25, and never below the JDK that built the jar. http-kit 2.8's `new-worker`
# picks its executor with a `compile-if` macro that evaluates `(Thread/ofVirtual)`
# at COMPILE time, so the JDK that builds the uberjar decides, permanently,
# whether the jar wants virtual threads. Built on 21 or later the answer is baked
# in as yes — and running the result on a 17 JVM dies with
# NoSuchMethodError: newVirtualThreadPerTaskExecutor, from deep inside integrant
# where it looks like anything but a JDK mismatch. Keep this at or above the
# version that builds the jar.
#
# And Ubuntu, on amd64 or arm64: the jar carries sqlite-jdbc's native library for
# glibc Linux on those two only (build/build.clj says why), so an Alpine base or
# another architecture would start and then fail to open the database.
FROM eclipse-temurin:25-jre-noble

# curl is for the healthcheck and nothing else. This base image already ships it,
# so the install is a no-op today and the line is here to keep it that way: a
# healthcheck that silently loses its HTTP client would leave the container stuck
# `unhealthy` forever, and because Caddy waits on `service_healthy` that reads as
# "the deploy hangs" rather than "curl is missing". Six seconds of build time to
# not debug that on a game night.
#
# The healthcheck earns its keep regardless: without one, Compose calls the app
# "up" the moment the JVM process exists, several seconds before it is listening.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Unprivileged, and the owner of /data. The SQLite file is created at boot by
# store/ensure-parent-dir!, so this user needs write access to the directory
# rather than to a file that does not exist yet. Docker seeds a fresh named
# volume from the image's directory, ownership included, so the mount inherits
# this and the app can write to it without a chown at startup.
# No --system: it caps uids at SYS_UID_MAX (999) and warns when handed 10001.
# The explicit high uid is the point — it cannot collide with a host user if the
# volume is ever bind-mounted instead of named.
RUN useradd --create-home --uid 10001 --shell /usr/sbin/nologin werwolf \
 && mkdir -p /data \
 && chown werwolf:werwolf /data

WORKDIR /app

# Exactly one match: build.clj cleans target/ before packing, so the glob cannot
# quietly pick up a stale jar from an older revision.
COPY --chown=werwolf:werwolf target/*-standalone.jar /app/werwolf.jar

USER werwolf

# Inside the container the log lives on the volume, not next to the jar. Every
# other setting is read from the environment by resources/config.edn; this one
# gets a default here because the image's filesystem layout is the image's
# business, not the operator's.
ENV DB_PATH=/data/werwolf.db \
    PORT=3000 \
    HOST=0.0.0.0

EXPOSE 3000

VOLUME ["/data"]

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -fsS http://127.0.0.1:3000/health || exit 1

# Exec form, so the JVM is PID 1 and receives SIGTERM directly — which is what
# lets werwolf.main's shutdown hook run ig/halt! and close the event log
# cleanly instead of being killed after the 10s grace period.
#
# MaxRAMPercentage rather than a fixed -Xmx: the container's limit is the only
# number that matters, and hardcoding a heap size here would be wrong on every
# host but the one it was chosen for.
#
# ExitOnOutOfMemoryError, so `restart: unless-stopped` gets something to restart:
# an OOM kills only the thread it hits, and when that was http-kit's selector
# the JVM lived on answering nothing. See nix/module.nix.
#
# --enable-native-access for sqlite-jdbc's native library: see nix/package.nix.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/werwolf.jar"]
