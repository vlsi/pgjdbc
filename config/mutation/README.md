# Mutation testing for the codec converters

This directory contains an **opt-in** [PIT](https://pitest.org) (a.k.a. *pitest*)
mutation-testing harness for the codec converters added on the `typecache`
branch (`org.postgresql.jdbc.codec.*`).

Mutation testing tells you *which lines your tests do not really exercise*. PIT
makes thousands of tiny edits ("mutants") to the compiled bytecode — flips a
`<` to `<=`, replaces a return value with `0`/`null`, removes a method call, …
— and re-runs the tests. If a test fails, the mutant is **killed** (good, the
behaviour on that line is pinned down). If every test still passes, the mutant
**survived** (a real gap: that line can change behaviour without any test
noticing).

## Why an init script instead of editing the build

The harness is a Gradle **init script** (`pitest.init.gradle`) applied with
`--init-script`. The normal pgjdbc build is left completely untouched: no new
plugin in `build.gradle.kts`, nothing downloaded unless you actually ask for a
mutation run. This keeps the prototype low-risk and easy to drop or evolve.

## Quick start

No database needed (fast, portable — runs the 43 pure-unit codec tests):

```bash
config/mutation/run-codec-mutation.sh
# or directly:
./gradlew :postgresql:pitestCodec --init-script config/mutation/pitest.init.gradle
```

Open the HTML report:

```
pgjdbc/build/reports/pitest-codec/index.html
```

Get a ranked, plain-text "test these lines more" summary from the XML:

```bash
python3 config/mutation/summarize_mutations.py \
    pgjdbc/build/reports/pitest-codec/mutations.xml
```

## Including the database-backed codec tests

Five codec tests open a real JDBC connection and are **excluded by default**
(so the command above works with zero infrastructure):

```
CodecIntegrationTest  CompositeFormatTest  CompositeEscapingTest
ArrayWalkerCatchAllTest  NestingDepthTest
```

These are the main coverage for `CompositeCodec`, `ArrayCodec`, `RangeCodec`
and the domain/array walking paths, so a unit-only run will *understate* how
well those classes are tested. To let them contribute, start the server and
pass an empty exclude list:

```bash
(cd docker/postgres-server && PGV=16 docker compose up -d)
config/mutation/run-codec-mutation.sh --with-db
```

The test connection parameters come from `build.properties`
(`localhost:5432`, db `test`, user `test`) — exactly what
`docker/postgres-server` provisions.

## Focusing on one codec

Mutation runs are slow; while iterating on a single converter, scope it:

```bash
config/mutation/run-codec-mutation.sh --target org.postgresql.jdbc.codec.NumericCodec
# (passes both -Ppitest.targetClasses and -Ppitest.targetTests)
```

## Tuning knobs (all `-P` properties)

| property                       | default                            | meaning                                   |
|--------------------------------|------------------------------------|-------------------------------------------|
| `pitest.targetClasses`         | `org.postgresql.jdbc.codec.*`      | classes to mutate                         |
| `pitest.targetTests`           | `org.postgresql.jdbc.codec.*`      | tests to run                              |
| `pitest.excludedTestClasses`   | the 5 DB tests above               | tests to skip (set empty to include them) |
| `pitest.excludedClasses`       | `*Test,*Test$*,*Tests,*IT…`        | classes **not** to mutate (codec tests share the package) |
| `pitest.mutators`              | `DEFAULTS`                         | `DEFAULTS` / `STRONGER` / `ALL` / list    |
| `pitest.threads`               | `4`                                | parallel mutation workers                 |
| `pitest.features`              | *(none)*                           | extra PIT features, e.g. `+EXPORT`        |
| `pitest.reportDir`             | `pitest-codec`                     | report sub-dir under `build/reports/`     |
| `pitest.version`               | `1.17.4`                           | PIT version                               |
| `pitest.junit5Version`         | `1.2.2`                            | `pitest-junit5-plugin` version            |

## Reading the report

- **KILLED / TIMED_OUT** — a test caught the mutation. 
- **SURVIVED** — the line runs but no assertion checks the result. *Add an
  assertion.*
- **NO_COVERAGE** — no test touches the line at all. *Add a test.*
- **NON_VIABLE / RUN_ERROR / MEMORY_ERROR** — PIT bookkeeping, not a test gap.

`summarize_mutations.py` ranks classes by `SURVIVED + NO_COVERAGE` and then
lists each gap as `Lnn [SURVIVE|NO-COV] method(): description (mutator)` so you
can jump straight to the lines that need attention.
