#!/usr/bin/env bash
#
# Copyright (c) 2024, PostgreSQL Global Development Group
# See the LICENSE file in the project root for more information.
#
# Convenience runner for codec mutation testing (PIT).
#
#   config/mutation/run-codec-mutation.sh                 # fast, no database
#   config/mutation/run-codec-mutation.sh --with-db       # include DB-backed codec tests
#   config/mutation/run-codec-mutation.sh --target org.postgresql.jdbc.codec.NumericCodec
#
# When --with-db is given, start the server first:
#   (cd docker/postgres-server && PGV=16 docker compose up -d)
#
set -euo pipefail

cd "$(dirname "$0")/../.."   # repo root

INIT_SCRIPT="config/mutation/pitest.init.gradle"
GRADLE_ARGS=(":postgresql:pitestCodec" "--init-script" "$INIT_SCRIPT"
             "-PskipCheckstyle" "-PskipAutostyle" "--console=plain")

WITH_DB=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-db)
      WITH_DB=1
      shift
      ;;
    --target)
      GRADLE_ARGS+=("-Ppitest.targetClasses=$2" "-Ppitest.targetTests=$2")
      shift 2
      ;;
    *)
      # pass through any extra -P.. flags
      GRADLE_ARGS+=("$1")
      shift
      ;;
  esac
done

if [[ "$WITH_DB" == "1" ]]; then
  echo ">> Including database-backed codec tests (server must be running)"
  GRADLE_ARGS+=("-Ppitest.reportDir=pitest-codec-db" "-Ppitest.excludedTestClasses="
    "-Ppitest.jvmArgs=-Xmx2048m,-Dtest.url.PGHOST=localhost,-Dtest.url.PGPORT=5432,-Dtest.url.PGDBNAME=test,-Duser=test,-Dpassword=test")
  REPORT="pitest-codec-db"
else
  echo ">> Unit-only run (no database). Use --with-db to include integration tests."
  GRADLE_ARGS+=("-Ppitest.reportDir=pitest-codec-unit")
  REPORT="pitest-codec-unit"
fi

echo ">> ./gradlew ${GRADLE_ARGS[*]}"
./gradlew "${GRADLE_ARGS[@]}"

echo
echo ">> HTML report : pgjdbc/build/reports/${REPORT}/index.html"
echo ">> Gap summary : python3 config/mutation/summarize_mutations.py pgjdbc/build/reports/${REPORT}/mutations.xml"
