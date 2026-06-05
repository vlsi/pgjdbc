# syntax=docker/dockerfile:1
# PostgreSQL development snapshot image, made drop-in compatible with the
# official `postgres:*` image. The same docker/postgres-server compose file
# and scripts run against it by pointing PG_IMAGE at this image.
#
# Uses apt.postgresql.org's `*-pgdg-snapshot` suite, which is rebuilt from the
# upstream packaging git on every change (effectively sub-daily). The configure
# flags match the released pgdg builds (GSS, LLVM/JIT, ICU, SSL, ...).
#
# The pgdg-snapshot suite only carries the current snapshot, so each `docker
# build` resolves to whatever is current at build time. Reproducibility is
# provided by the published, dated GHCR tags (e.g. `devel-YYYY-MM-DD`).
#
# Multi-arch: amd64 and arm64 packages are published by pgdg, so this builds
# natively for both.

FROM debian:trixie-slim

# Empty default => pick the highest postgresql-N available in pgdg-snapshot
# at build time, so the image follows new major releases without a Dockerfile
# bump. Pass `--build-arg PG_MAJOR=18` to pin a specific major.
ARG PG_MAJOR=""

# PATH points at a stable symlink populated below; PG_MAJOR is exported by the
# shared docker/postgres-server entrypoint from `postgres --version` at runtime.
ENV PATH=/usr/lib/postgresql/current/bin:$PATH \
    PGDATA=/var/lib/postgresql/data \
    LANG=en_US.utf8

# Match the official image's postgres user (uid/gid 999) so volumes and bind
# mounts stay portable between this image and the official one.
RUN set -eux; \
    groupadd -r postgres --gid=999; \
    useradd -r -g postgres --uid=999 --home-dir=/var/lib/postgresql --shell=/bin/bash postgres

# Cache apt downloads so repeated local builds skip re-fetching. The cache
# mounts live in the builder, not the image, so the lists never reach a layer
# (no rm needed). docker-clean is removed so apt keeps the .debs in the cache.
ARG PG_MAJOR
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    set -eux; \
    export DEBIAN_FRONTEND=noninteractive; \
    rm -f /etc/apt/apt.conf.d/docker-clean; \
    apt-get update -qq; \
    apt-get -y -qq install --no-install-recommends \
      ca-certificates curl gosu locales; \
    install -d /usr/share/postgresql-common/pgdg; \
    curl -fsS https://www.postgresql.org/media/keys/ACCC4CF8.asc \
      -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc; \
    . /etc/os-release; \
    # Enable every numeric component (one per major) that the snapshot ships,
    # so apt-cache search can find postgresql-N for any major.
    COMPONENTS="$(curl -fsS "http://apt.postgresql.org/pub/repos/apt/dists/$VERSION_CODENAME-pgdg-snapshot/Release" \
        | awk '/^Components:/ {for (i=2; i<=NF; i++) print $i}' | tr '\n' ' ')"; \
    echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] \
http://apt.postgresql.org/pub/repos/apt $VERSION_CODENAME-pgdg-snapshot $COMPONENTS" \
      > /etc/apt/sources.list.d/pgdg-snapshot.list; \
    apt-get update -qq; \
    # If PG_MAJOR was not provided as a build-arg, pick the highest available.
    if [ -z "${PG_MAJOR:-}" ]; then \
      PG_MAJOR="$(apt-cache search --names-only '^postgresql-[0-9]+$' | awk '{print $1}' | sed 's/postgresql-//' | sort -n | tail -n1)"; \
    fi; \
    [ -n "$PG_MAJOR" ] || { echo "Could not determine PG_MAJOR"; exit 1; }; \
    echo "Installing PostgreSQL major $PG_MAJOR"; \
    # -t targets the snapshot suite explicitly; pgdg-snapshot has lower apt
    # priority by default, so without -t apt prefers older stable libpq5 and
    # the snapshot's version dependency can't be satisfied.
    apt-get -y -qq install --no-install-recommends \
      -t "$VERSION_CODENAME-pgdg-snapshot" \
      "postgresql-$PG_MAJOR" "postgresql-contrib-$PG_MAJOR"; \
    localedef -i en_US -c -f UTF-8 en_US.UTF-8; \
    # Stable paths for ENV PATH / postgresql.conf.sample editing.
    ln -sfn "/usr/lib/postgresql/$PG_MAJOR" /usr/lib/postgresql/current; \
    ln -sfn "/usr/share/postgresql/$PG_MAJOR" /usr/share/postgresql/current; \
    dpkg-query -W "postgresql-$PG_MAJOR" | tee /usr/local/share/postgresql-version

# Mirror the official image layout: a writable PGDATA, /docker-entrypoint-initdb.d,
# and /var/run/postgresql with sticky permissions.
RUN set -eux; \
    mkdir -p /var/lib/postgresql "$PGDATA" /docker-entrypoint-initdb.d /var/run/postgresql; \
    chown -R postgres:postgres /var/lib/postgresql "$PGDATA" /var/run/postgresql; \
    chmod 1777 /var/run/postgresql; \
    echo "listen_addresses = '*'" >> /usr/share/postgresql/current/postgresql.conf.sample

# Reuse the official entrypoint (pinned commit) rather than maintaining a fork.
ADD --chmod=0755 https://raw.githubusercontent.com/docker-library/postgres/dc8f7ae06a43a6c9647d9b7ca3b270bd148307fb/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

WORKDIR /var/lib/postgresql
VOLUME /var/lib/postgresql/data
EXPOSE 5432
STOPSIGNAL SIGINT
ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["postgres"]
