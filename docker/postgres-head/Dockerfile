# syntax=docker/dockerfile:1
# PostgreSQL built from source (development HEAD), made drop-in compatible with
# the official `postgres:*` image. The same docker/postgres-server compose file
# and scripts run against it by pointing PG_IMAGE at this image.
#
# Multi-stage: the builder carries the full toolchain and source tree; the final
# image keeps only the installed server plus its runtime libraries, which are
# discovered automatically from what the binaries actually link against.
#
# The image is multi-arch clean (linux/amd64 and linux/arm64): nothing here is
# architecture-specific, so `docker buildx build --platform ...` works as-is.

ARG GIT_URL="https://github.com/postgres/postgres"
ARG GIT_TAG="master"
ARG GIT_SHA

FROM ubuntu:26.04@sha256:f3d28607ddd78734bb7f71f117f3c6706c666b8b76cbff7c9ff6e5718d46ff64 AS builder

# Cache apt downloads so repeated local builds skip re-fetching. The cache mounts
# live in the builder, not the image, so the lists never reach a layer (no rm
# needed) and CI's ephemeral builder is simply unaffected. docker-clean is
# removed so apt keeps the downloaded .debs in the cache mount.
ARG TARGETARCH
RUN --mount=type=cache,target=/var/cache/apt,id=apt-cache-${TARGETARCH},sharing=locked \
    --mount=type=cache,target=/var/lib/apt,id=apt-lib-${TARGETARCH},sharing=locked \
    export DEBIAN_FRONTEND=noninteractive \
    && rm -f /etc/apt/apt.conf.d/docker-clean \
    && apt-get update -qq \
    && apt-get -y -qq install --no-install-recommends \
    bison \
    build-essential \
    ca-certificates \
    ccache \
    flex \
    git \
    libicu-dev \
    liblz4-dev \
    libperl-dev \
    libreadline-dev \
    libssl-dev \
    libxml2-dev \
    libzstd-dev \
    pkg-config \
    python3-dev \
    tcl-dev \
    uuid-dev \
    zlib1g-dev

# Route the compiler through ccache (Debian ships wrappers in /usr/lib/ccache),
# so the make step below can reuse cached object files across local rebuilds.
ENV PATH=/usr/lib/ccache:$PATH

ARG GIT_URL
ARG GIT_TAG
# CI passes the resolved commit so the source layer is reproducible.
ARG GIT_SHA
RUN git clone -q -b "${GIT_TAG}" --single-branch --depth 10 "${GIT_URL}" /build/postgres \
    && cd /build/postgres \
    && { [ -z "${GIT_SHA:-}" ] || git reset --hard "${GIT_SHA}"; }

# --enable-cassert is deliberate: it surfaces backend assertion failures while
# testing the driver against unreleased PostgreSQL. JIT (--with-llvm) is left out
# on purpose: it is transparent to the wire protocol and only bloats the image.
ARG CONFIGURE_OPTS="\
    --enable-debug \
    --enable-cassert \
    --with-icu \
    --with-libxml \
    --with-lz4 \
    --with-perl \
    --with-python \
    --with-ssl=openssl \
    --with-tcl \
    --with-zstd \
    "
ARG CONFIGURE_ADD_OPTS
WORKDIR /build/postgres
# -q hides the "checking ..." probes; dump config.log on failure so the cause is visible.
RUN ./configure -q ${CONFIGURE_OPTS} ${CONFIGURE_ADD_OPTS:-} \
    || { echo "configure failed; tail of config.log:"; tail -n 100 config.log; exit 1; }

# world-bin builds core, contrib (hstore, sslinfo, ...) and the PLs without docs.
# -s (silent) hides the per-file compiler command lines; compiler warnings and
# errors still go to stderr, so a failure shows the offending file. The ccache
# mount persists object files across local rebuilds (cold and discarded in CI).
ARG MAKE_ADD_OPTS
RUN --mount=type=cache,target=/ccache,id=ccache-${TARGETARCH},sharing=locked \
    export CCACHE_DIR=/ccache \
    && make -j "$(nproc)" -s ${MAKE_ADD_OPTS:-} world-bin \
    && make -s install-world-bin \
    && ccache -s

# The official image listens on all interfaces by default; initdb copies this
# sample into the generated postgresql.conf, so the server is reachable from the
# host (and through Docker port mapping) without extra configuration.
RUN echo "listen_addresses = '*'" >> /usr/local/pgsql/share/postgresql.conf.sample

# Record the exact runtime packages the installed binaries need, so the final
# image installs those and nothing else. Recomputed on every build, so a base
# image bump cannot leave the list stale.
RUN set -eux; \
    { ldd /usr/local/pgsql/bin/* || true; \
      find /usr/local/pgsql/lib -type f -name '*.so*' -exec ldd '{}' ';' || true; } 2>/dev/null \
      | awk '/=> \// { print $3 }' \
      | sort -u \
      | xargs -r dpkg -S 2>/dev/null \
      | awk -F: '{ print $1 }' \
      | sort -u > /usr/local/pgsql/.runtime-deps; \
    echo "Runtime packages:"; cat /usr/local/pgsql/.runtime-deps


FROM ubuntu:26.04@sha256:f3d28607ddd78734bb7f71f117f3c6706c666b8b76cbff7c9ff6e5718d46ff64

COPY --from=builder /usr/local/pgsql /usr/local/pgsql

ARG TARGETARCH
RUN --mount=type=cache,target=/var/cache/apt,id=apt-cache-${TARGETARCH},sharing=locked \
    --mount=type=cache,target=/var/lib/apt,id=apt-lib-${TARGETARCH},sharing=locked \
    set -eux; \
    export DEBIAN_FRONTEND=noninteractive; \
    rm -f /etc/apt/apt.conf.d/docker-clean; \
    apt-get update -qq; \
    apt-get -y -qq install --no-install-recommends \
      $(cat /usr/local/pgsql/.runtime-deps) \
      gosu \
      locales; \
    localedef -i en_US -c -f UTF-8 en_US.UTF-8
ENV LANG=en_US.utf8

ENV PATH=/usr/local/pgsql/bin:$PATH
ENV PGDATA=/var/lib/postgresql/data
# Recorded for traceability (visible in logs and SELECT version()).
ARG GIT_TAG
ARG GIT_SHA
ENV GIT_TAG=$GIT_TAG
ENV GIT_SHA=$GIT_SHA

# Mirror the official postgres image layout so this is a true drop-in.
RUN set -eux; \
    groupadd -r postgres --gid=999; \
    useradd -r -g postgres --uid=999 --home-dir=/var/lib/postgresql --shell=/bin/bash postgres; \
    mkdir -p /var/lib/postgresql "$PGDATA" /docker-entrypoint-initdb.d /var/run/postgresql; \
    chown -R postgres:postgres /var/lib/postgresql "$PGDATA" /var/run/postgresql; \
    chmod 1777 /var/run/postgresql

# Reuse the official entrypoint (pinned commit) rather than maintaining a fork.
ADD --chmod=0755 https://raw.githubusercontent.com/docker-library/postgres/dc8f7ae06a43a6c9647d9b7ca3b270bd148307fb/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

WORKDIR /var/lib/postgresql
VOLUME /var/lib/postgresql/data
EXPOSE 5432
STOPSIGNAL SIGINT
ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["postgres"]
