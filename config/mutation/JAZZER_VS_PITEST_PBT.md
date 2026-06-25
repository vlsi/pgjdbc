# Jazzer (coverage-guided fuzzing) vs PIT and property-based testing

Prototype: `NumericCodecFuzzTest` (a Jazzer `@FuzzTest`) in `pgjdbc-instancio-test`.
This evaluates the user's hypothesis that a *coverage-driven* tool might fit "increase
coverage" better than PIT/PBT.

## They answer three different questions — not the same one

| | what it actually does | answers |
|---|---|---|
| **PIT** (mutation) | mutates the *code*, reruns your *existing tests*; generates **no** inputs | "are my **assertions** strong?" (test quality) |
| **PBT** (Instancio / jetCheck) | generates **typed values**, you assert a property | "does this **property** hold over many values?" |
| **Jazzer** (fuzzing) | mutates **raw bytes** with **coverage feedback** to reach new branches; built on libFuzzer + JVM instrumentation | "what **input** reaches this branch / breaks this parser?" |

So "increase code coverage" is **literally Jazzer's objective function** — it evolves inputs to
maximize edge coverage. On a *parser* it reaches branches that random PBT and example tests do not.
But two caveats keep it from *replacing* PIT/PBT:

1. **Coverage ≠ tested behavior.** Reaching a line doesn't assert it's right. Without an oracle,
   Jazzer finds only **crashes** (uncaught exceptions, OOM, infinite loops). PIT measures whether
   your *assertions* pin behavior — a stricter bar than execution. They are complementary: Jazzer
   *discovers inputs*, an oracle turns them into pass/fail, PIT *scores the assertions*.
2. **You don't run Jazzer under PIT** (fuzzing once per mutant is absurd). The pipeline is
   sequential: *fuzz → harvest crashing/coverage-increasing inputs → commit them as a corpus →*
   they replay deterministically in CI and are then scored by PIT.

## Why the codec layer is a textbook fuzz target — and the prototype result

The **decode** direction parses **untrusted wire data** (server bytes, or user strings via
`setObject(String, …)`): hand-rolled parsers like `LiteralCursor` (array/composite literals),
`bytea` hex/escape, the `numeric`/temporal **binary headers**, `hstore`, `json`, `range`. Those have
deep/rare branches reachable only by specific malformed inputs — exactly what coverage-guided
fuzzing evolves toward, and what robustness depends on (a malicious/buggy server or corrupted stream
should not OOM, hang, or throw undeclared exceptions).

**The prototype found real issues in minutes, on JDK 21, that PBT + PIT structurally cannot:**

- **6 s, regression mode** — `NumericCodec.decodeBinary` throws
  `IllegalArgumentException: number of bytes should be at-least 8` on a short buffer: an
  **undeclared** exception (the contract is `SQLException`). PBT never feeds a 3-byte `numeric` — it
  round-trips *valid* values; PIT can't generate inputs at all.
- **2 s, fuzzing mode (23 950 execs)** — `java.lang.ArithmeticException: Rounding necessary` on the
  **decode→encode round-trip**: a fuzzer-crafted binary `numeric` decodes to a `BigDecimal` that the
  *encoder* then cannot re-encode without throwing. My own PBT round-trip tests missed this — they
  generate ordinary Java `BigDecimal`s and go encode→decode; Jazzer crafted a *wire* value going the
  other way. (Worth the maintainer triaging as a real round-trip asymmetry in
  `NumericCodec`/`ByteConverter`.)

That is the user's intuition, demonstrated: for the parser/decode paths, coverage-guided fuzzing
both raises branch coverage and finds robustness bugs the value-oriented tools don't.

## Tradeoffs

| dimension | PIT | PBT (Instancio/jetCheck) | **Jazzer** |
|---|---|---|---|
| generates inputs | no | yes (typed values) | **yes (bytes, coverage-guided)** |
| needs an oracle for logic bugs | n/a (scores tests) | yes | yes — else only crashes |
| finds crashes on untrusted input (OOM, AIOOBE, NPE, hangs) | weak | weak | **strong** |
| reaches deep parser branches | only via the tests you wrote | random — often misses | **evolves toward them** |
| determinism | deterministic | deterministic (pinned seed) | **regression mode** deterministic; **fuzzing mode** not |
| CI shape | one task | one task | **two**: fast regression gate + separate time-boxed fuzz job / OSS-Fuzz |
| infra weight | JVM only | pure-Java lib | **native libFuzzer driver + JVM instrumentation agent**, per-platform |
| best for | scoring assertion quality | scalar/value codecs (small space, oracle) | **parser/decode of untrusted input** |
| maintenance / license | active, Apache-2.0 | Instancio active / jetCheck sleepy; Apache-2.0 | active (Code Intelligence, powers OSS-Fuzz), Apache-2.0 |

## CI shape (the practical cost)

- **Regression mode** (default `@FuzzTest`, no `JAZZER_FUZZ`): replays the committed corpus + the
  empty seed — fast, deterministic, **PIT-scoreable**, fine in the normal gate.
- **Fuzzing mode** (`JAZZER_FUZZ=1`): time-boxed, non-deterministic exploration — belongs in a
  **nightly/dedicated job**, or onboard to **OSS-Fuzz** (free continuous fuzzing for OSS, which is
  *the* idiomatic home for a JVM fuzzer and how projects sustain it).
- This is materially more machinery than dropping in a PBT library, plus corpus/crash-input
  maintenance and a native toolchain on the runners.

## Recommendation for pgjdbc

The user is right for the **parser/decode** codecs: Jazzer is the best of the three at *reaching*
those branches and at finding robustness bugs on untrusted wire data — and it already surfaced two.
But it is **complementary, not a replacement** — it is an *input-discovery + crash* stage, not a
test-quality scorer (PIT) or a value-property generator (PBT). A sensible split:

- **scalar/value codecs** (`int*`, `float*`, `numeric` values): PBT (Instancio/jetCheck) + PIT —
  small input space, oracle-driven, deterministic.
- **parser/decode codecs** (`LiteralCursor`, array/composite, `range`, `hstore`, `json`, `bytea`,
  binary headers): **Jazzer** with a robustness + round-trip/differential oracle; harvested corpus
  replayed in the gate and **scored by PIT**; ideally wired to **OSS-Fuzz** for continuous runs.

Think of it as a pipeline, four tools / four jobs:

```
Jazzer (discover inputs, coverage-guided)  ->  oracle / example + PBT (assert behavior)
        ->  PIT (verify the assertions are strong)  ->  JaCoCo (see which lines were reached)
```

## Reproduce

```bash
./gradlew :pgjdbc-instancio-test:test --tests '*NumericCodecFuzzTest*'        # regression (fast, green)
JAZZER_FUZZ=1 ./gradlew :pgjdbc-instancio-test:test --tests '*NumericCodecFuzzTest*' --no-daemon
# ^ fuzzing mode reproduced the ArithmeticException above in ~2 s on JDK 21.
```
