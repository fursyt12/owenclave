# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Stage 1: Fast prebuilt target (`./gradlew :desktop:app:prepareServerDist &&
#          docker build --target prebuilt -t owenclave:latest .`)
# Placed first so legacy `docker build` (without BuildKit) skips the Gradle
# builder stage when `--target prebuilt` is requested.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble AS prebuilt

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl iproute2 iptables nftables tzdata \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/owenclave
COPY desktop/app/build/server-dist/ /opt/owenclave/
COPY docker/entrypoint.sh /usr/local/bin/owenclave-entrypoint
RUN chmod +x /usr/local/bin/owenclave-entrypoint /opt/owenclave/bin/* 2>/dev/null || true \
  && mkdir -p /config /data

VOLUME ["/config", "/data"]
EXPOSE 7890/tcp 7890/udp 7893/tcp 7893/udp 9090/tcp

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD bash -c 'ADDR="${EXTERNAL_CONTROLLER:-127.0.0.1:9090}"; PORT="${ADDR##*:}"; curl -fsS "http://127.0.0.1:${PORT}/healthz" >/dev/null'

ENTRYPOINT ["/usr/local/bin/owenclave-entrypoint"]
CMD ["--daemon"]

# ---------------------------------------------------------------------------
# Stage 2: Full JVM + runtime builder (used by default `docker build .`)
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-noble AS builder

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl git python3 xz-utils \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /src
COPY . .

# Ensure NaiveProxy, tun2socks, and core binaries exist (reuses prebuilt dist/ if present)
RUN set -eux; \
    ARCH="$(dpkg --print-architecture)"; \
    case "$ARCH" in amd64) TAG="linux-amd64"; TARGET="linux/amd64" ;; arm64) TAG="linux-arm64"; TARGET="linux/arm64" ;; *) TAG="linux-amd64"; TARGET="linux/amd64" ;; esac; \
    if [ ! -f "desktop/naive/dist/$TAG/naive" ]; then TARGETS="$TARGET" ./bin/desktop/naive/download.sh; fi; \
    if [ ! -f "desktop/tun2socks/dist/$TAG/tun2socks" ]; then TARGETS="$TARGET" ./bin/desktop/tun2socks/download.sh; fi; \
    ./gradlew :desktop:app:prepareServerDist --no-daemon --console=plain

# ---------------------------------------------------------------------------
# Stage 3: Default runtime image built from Stage 2
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble AS runtime

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl iproute2 iptables nftables tzdata \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/owenclave
COPY --from=builder /src/desktop/app/build/server-dist/ /opt/owenclave/
COPY docker/entrypoint.sh /usr/local/bin/owenclave-entrypoint
RUN chmod +x /usr/local/bin/owenclave-entrypoint /opt/owenclave/bin/* \
  && mkdir -p /config /data

VOLUME ["/config", "/data"]
EXPOSE 7890/tcp 7890/udp 7893/tcp 7893/udp 9090/tcp

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD bash -c 'ADDR="${EXTERNAL_CONTROLLER:-127.0.0.1:9090}"; PORT="${ADDR##*:}"; curl -fsS "http://127.0.0.1:${PORT}/healthz" >/dev/null'

ENTRYPOINT ["/usr/local/bin/owenclave-entrypoint"]
CMD ["--daemon"]
