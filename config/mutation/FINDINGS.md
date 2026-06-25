# Codec mutation-testing findings — where the codecs need more tests

Generated with the PIT harness in this directory (see `README.md`) against the
codec converters added on the `typecache` branch
(`org.postgresql.jdbc.codec.*`, ~13 k LOC, 54 classes). Two runs:

| run | tests used | mutations | killed | no-coverage | test strength | line cov |
|-----|------------|-----------|--------|-------------|---------------|----------|
| **unit-only** (no DB) | 43 codec unit tests | 3109 | 1297 (**42 %**) | 1423 | 77 % | 50 % |
| **+ database** | all 48 codec tests | 3109 | 1420 (**46 %**) | 1262 | 77 % | 55 % |

Reproduce:

```bash
config/mutation/run-codec-mutation.sh                 # unit-only
(cd docker/postgres-server && PGV=16 docker compose up -d)
config/mutation/run-codec-mutation.sh --with-db       # + integration tests
python3 config/mutation/summarize_mutations.py pgjdbc/build/reports/pitest-codec-db/mutations.xml
python3 config/mutation/compare_runs.py \
    pgjdbc/build/reports/pitest-codec-unit/mutations.xml \
    pgjdbc/build/reports/pitest-codec-db/mutations.xml
```

## How to read this

**Test strength is already 77 %** — when a codec line *is* exercised, the tests
usually pin it down. The problem is **coverage**: 1262 mutations (even with the
database) live on lines no test ever runs. So the headline is *breadth*, not
assertion quality. The work splits into three tiers.

---

## Tier 1 — no coverage from ANY test (write tests first)

These codecs have **no `*Test` file** and are not meaningfully reached by the
integration tests either. Every mutation survives. Highest value per test added.

| codec | mutations | score | note |
|-------|-----------|-------|------|
| `PGobjectCodec`  | 40 | **0 %** | all 40 mutations uncovered; the generic `PGobject`/extension-type path |
| `NameCodec`      | 23 | **0 %** | `name` type encode/decode + `decodeAsInt`/`decodeAsLong` conversions |
| `BpcharCodec`    | 23 | **0 %** | blank-padded `char(n)` — trailing-space semantics untested |
| `HstoreCodec`    | 21 | **0 %** | `hstore` binary+text map parsing (the DB even loads the extension) |
| `ArrayLeafCodec` | 8  | 12 %    | abstract leaf base — the shared `shape/validate` helpers |

## Tier 2 — covered ONLY by the (slow) database tests (add fast unit tests)

0 % under unit tests, non-zero only once the integration tests run. They work,
but the only thing pinning them down is a full wire round-trip. A direct unit
test (like the other `*CodecTest`s) would be faster and far more thorough.

| codec | unit | +DB | mutations |
|-------|------|-----|-----------|
| `ArrayCodec`          |  0 % | 21 % | 129 |
| `MoneyArrayLeafCodec` |  0 % | 18 % | 40 |
| `JsonArrayLeafCodec`  |  0 % | 18 % | 38 |
| `MoneyCodec`          |  0 % | 14 % | 28 |
| `DomainCodec`         |  0 % |  5 % | 88 |
| `BitCodec`            |  0 % |  2 % | 64 |

`ArrayCodec` (129 mutations) and `DomainCodec` (88) are large and central to the
codec walker — at 21 % / 5 % they are the biggest absolute gaps in the branch.

## Tier 3 — unit-tested but under-asserted (strengthen existing tests)

These *have* a test, but a large share of branches still survive. Worst first:

| codec | score | mutations | what survives |
|-------|-------|-----------|---------------|
| `NumericCodec`        | 17 % | 118 | NaN / ±Infinity + null branches (see below) |
| `Float8Codec`         | 16 % | 85  | `decodeAs*` overflow/NaN guards |
| `Float4Codec`         | 17 % | 81  | `decodeAs*` overflow/NaN guards |
| `RangeCodec`          | 17 % | 111 | empty / infinite / inclusive-exclusive bound combinations |
| `Int2Codec`           | 29 % | 58  | overflow / range-check branches |
| `TimetzCodec`         | 34 % | 64  | offset & micro/nano boundary handling |
| `TimeCodec`           | 34 % | 61  | 24:00 / midnight & precision boundaries |
| `GeometricCodec$Binary` | 37 % | 43 | point/box/path/polygon binary shapes |
| `TimestamptzCodec`    | 38 % | 81  | infinity timestamps & TZ rounding |
| `CompositeCodec`      | 42 % | 206 | NULL fields, quoting, nested composites |
| `TimestampCodec`/`DateCodec` | 45/31 % | 86/65 | BC dates, infinity, overflow |
| `FallbackCodec`       | 27 % | 51  | the unknown-type byte/`PGobject` passthrough |

### Concrete examples (exact lines)

`NumericCodec` — the special-value branches are never asserted. A test that
decodes binary **and** text `NaN`, `Infinity`, `-Infinity` would kill ~10 of these:

```
NumericCodec.java
  L71  decodeBinary  if (result instanceof Double)        SURVIVED
  L73  decodeBinary  if (isNaN || isInfinite)             NO_COVERAGE  (binary NaN/Inf)
  L74  decodeBinary  return d                             NO_COVERAGE
  L77  decodeBinary  if (result instanceof BigDecimal)    SURVIVED
  L98  decodeText    "NaN".equalsIgnoreCase(...)           SURVIVED
  L101 decodeText    "Infinity"/"+Infinity"...            SURVIVED   (text Inf)
```

`LiteralCursor` (array/composite literal parser, 63→71 % with DB) — every
`skipWhitespace()` can be deleted unnoticed, and the brace-counting bounds are
not pinned: lines **85, 94, 108, 122, 136** (`VoidMethodCall` on
`skipWhitespace`) and **137-141, 154-157** (`ConditionalsBoundary` in
`skipDimensionPrefix` / `countLeadingBraces`). Add assertions on parsing inputs
with irregular whitespace and explicit array-dimension prefixes (`[1:3]=`).

Recurring pattern across the scalar codecs: `RemoveConditional_EQUAL_ELSE`
survives on the `decodeAsInt/Long/Double/Boolean` guard checks — the conversion
helpers are called but their **range/overflow/null guards are never asserted**.
Cover each `decodeAs*` with an out-of-range and a null input.

Full per-line lists:

```bash
python3 config/mutation/summarize_mutations.py \
    pgjdbc/build/reports/pitest-codec-db/mutations.xml | less
```

---

## Suggested order of work

1. **Tier 1** — add `PGobjectCodecTest`, `NameCodecTest`, `BpcharCodecTest`,
   `HstoreCodecTest`. Cheap, each goes 0 % → high.
2. **Tier 2** — add unit tests for `ArrayCodec` and `DomainCodec` first (largest
   classes), then `MoneyCodec`, `BitCodec`, and the money/json array leaves.
3. **Tier 3** — extend the existing tests for `NumericCodec`, the two float
   codecs, `RangeCodec`, and the temporal codecs with NaN/Infinity, overflow,
   null, and boundary inputs.

## Instancio property-test spike (`pgjdbc-instancio-test`)

To bump these numbers we prototyped property-based tests with
[Instancio](https://www.instancio.org/) in a new Java-17 module,
`pgjdbc-instancio-test` (gated on `jdkTestVersion >= 17` in `settings.gradle.kts`,
package `org.postgresql.test.codec`). The tests feed boundary cases plus
Instancio-generated random values through each codec's own `decode(encode(x))`
oracle, with a fixed `@Seed` so PIT reruns stay deterministic. Variable-length
data (e.g. `bytea`) is bounded and, on failure, shrunk by a small manual
`Minimizer` (delta-debugging) — Instancio does not shrink, which matters for the
large counterexamples variable-length codecs (arrays/structs) can produce.

Measure the lift (scoped to the six numeric/bytea codecs the spike targets):

```bash
# before = existing unit tests only; after = unit tests + Instancio
python3 config/mutation/compare_runs.py \
    pgjdbc/build/reports/pitest-cmp-before/mutations.xml \
    pgjdbc/build/reports/pitest-cmp-after/mutations.xml before% after%
```

**The key lesson — aim at the survivors, don't just round-trip.** A naive
`decodeBinary(encodeBinary(x))` property barely moved the score (`NumericCodec`
**16 % → 19 %**): it mostly re-covers the happy path the existing unit tests
already hit. Re-pointing the same Instancio data at the *surviving branches* —
`decodeText` and the `decodeAs*` conversions over NaN / ±Infinity / overflow —
lifted one class sharply from a single extra test file:

| codec | before | after | Δ | how |
|-------|--------|-------|---|-----|
| `NumericCodec` | 16 % | **36 %** | **+19** | `decodeText` + `decodeAs{BigDecimal,Double,Float,Int,Long}` over finite + NaN/±Inf + overflow |
| `Int4`/`Int8`/`Float4`/`Float8`/`Bytea` | — | — | +0 | binary roundtrip only — re-covers existing tests; needs the same `decodeAs*`/text treatment to move |

So PBT works, but only when the generators drive the specific uncovered methods.
The same recipe applied to the float/integer `decodeAs*` paths and the Tier-1
zero-coverage codecs should move them similarly.

**Scope limit / follow-up.** The spike stays on *context-free* code paths so it
can pass a `null` `CodecContext` (the scalar binary/text/`decodeAs*` paths). The
context-dependent paths — text `int` parsing, the temporal codecs, and the
array/composite walkers — need a real test `CodecContext`. The clean way to
reach those from a separate module is to expose the existing `TestCodecContext`
(today in `:postgresql` test sources) via Gradle **test fixtures**; that is the
natural next step before extending the property tests to `RangeCodec`,
`CompositeCodec`, and the temporal family.

## Caveats

- `DEFAULTS` mutator set. Run with `-Ppitest.mutators=STRONGER` for more (return
  values, increments, inline constants) once Tier 1/2 gaps are closed.
- `api.codec.*` interfaces are intentionally not mutated (no executable code).
- Scores are *mutation* scores (killed/total), stricter than line coverage; 46 %
  with high test strength means "good assertions, missing breadth," not "bad
  tests."
