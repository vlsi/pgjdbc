Builds a PostgreSQL development image from `apt.postgresql.org`'s
`*-pgdg-snapshot` suite, which the upstream packagers refresh on every change
to the packaging git (effectively sub-daily). The image is drop-in compatible
with the official `postgres:*` image, so the shared
`docker/postgres-server/docker-compose.yml` runs it via `PG_IMAGE` and reuses
its SSL, XA, SCRAM, replicas and full set of test databases.

The configure flags match the released pgdg builds (`--with-gssapi`,
`--with-llvm`, `--with-icu`, ...), so the image covers feature coverage that a
hand-rolled source build would have to opt into.

Build and run locally:

    docker/bin/postgres-head

That builds `pgjdbc/postgres-devel:local` and starts it via the postgres-server
compose. To run a pre-built image instead, point the shared compose at it
directly:

    PG_IMAGE=ghcr.io/pgjdbc/postgres-devel:devel \
      docker compose -f docker/postgres-server/docker-compose.yml up

To build and debug straight from the shared compose, run from
`docker/postgres-server` (the compose builds `../postgres-head`):

    # build only, with the full build log
    PG_IMAGE=pgjdbc/postgres-devel:local docker compose build

    # rebuild and run
    PG_IMAGE=pgjdbc/postgres-devel:local docker compose up --build

    # force a build without the flag (pull_policy defaults to 'missing')
    PG_IMAGE=pgjdbc/postgres-devel:local PG_PULL_POLICY=build docker compose up

Set `PG_MAJOR` (default `19`) to pick a different major. The official
`postgres:*` path is unaffected: with the default pull_policy the build section
is ignored and the image is pulled.
