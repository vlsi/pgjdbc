Builds PostgreSQL from source (development HEAD) as an image that is drop-in
compatible with the official `postgres:*` image.

The layout matches the official image: same `docker-entrypoint.sh`, `PG_MAJOR`,
`PGDATA`, `gosu` and `/docker-entrypoint-initdb.d`. So the server runs through the
shared `docker/postgres-server/docker-compose.yml` and reuses its SSL, XA, SCRAM,
replicas and full set of test databases.

Build and run locally:

    docker/bin/postgres-head

That builds `pgjdbc/postgres-devel:local` and starts it via the postgres-server
compose. To run a pre-built image instead of compiling, point the shared compose
at it directly:

    PG_IMAGE=ghcr.io/pgjdbc/postgres-devel:devel \
      docker compose -f docker/postgres-server/docker-compose.yml up

To build and debug the source image straight from the shared compose, run from
`docker/postgres-server` (the compose builds `../postgres-head`):

    # build only, with the full build log
    PG_IMAGE=pgjdbc/postgres-devel:local docker compose build

    # rebuild and run
    PG_IMAGE=pgjdbc/postgres-devel:local docker compose up --build

    # force a build without the flag (pull_policy defaults to 'missing')
    PG_IMAGE=pgjdbc/postgres-devel:local PG_PULL_POLICY=build docker compose up

Set `PG_HEAD_BRANCH` or `PG_HEAD_SHA` to build a specific branch or commit. The
official `postgres:*` path is unaffected: with the default pull_policy the build
section is ignored and the image is pulled.
