# Instancio vs jetCheck for codec property tests

A hands-on comparison: the same codec properties were written with both libraries in
`pgjdbc-instancio-test` and run under the same PIT harness. Files:

| | Instancio | jetCheck |
|---|---|---|
| scalar conversions | `FloatConversionPropertyTest`, `NumericConversionPropertyTest` | `Float8JetCheckTest` |
| variable-length / shrinking | `ByteaVariableLengthPropertyTest` + `Minimizer` + `MinimizerTest` | `ByteaJetCheckTest` |

## Verdict

**Both are "good enough" to drive the codec mutation work — they have equivalent
killing power, because the kills come from the oracle, not the generator.** Measured on
`Float8Codec`, each library's property test running *alone*, on the *same surface*:

| test (alone) | mutation score | test strength | uncovered |
|---|---|---|---|
| Instancio `FloatConversionPropertyTest` | 21 % (18/85) | 86 % | 64 |
| jetCheck `Float8JetCheckTest` | **24 % (20/85)** | **95 %** | 64 |

Same 64 uncovered (methods neither test touches); among *covered* mutants jetCheck killed a
couple more (95 % vs 86 % strength) thanks to more per-iteration variation. (An earlier jetCheck
run scored 13 % — that was a *smaller surface* I'd written, not a framework deficit; matching the
surface closed the gap.) So the choice is **not** about mutation power. It is about shrinking,
ergonomics, determinism, and maintenance.

## The two are different *kinds* of tool

- **jetCheck is a property-based-testing framework** in the QuickCheck/Hypothesis line:
  composable `Generator`s (`map`/`flatMap`/`suchThat`/`anyOf`/`sampledFrom`), `forAll`,
  **automatic shrinking**, and stateful `checkScenarios(...)`. The natural fit for "fuzz the wire
  format and minimize any failure."
- **Instancio is a random-object *populator*.** It reflectively fills objects/records/beans/
  collections with random data (`Instancio.create`, `ofList`, selectors/overrides). It has **no
  `forAll`, no shrinking, no stateful** model — you bolt the property on top with an ordinary loop.
  Its real strength shows when you need a *complex populated object* (a composite/`SQLData` bean),
  not a fuzzed primitive.

## The same property, both ways (`Float8Codec.decodeAsInt` contract)

```java
// Instancio: a plain @Test loop; checked exceptions just propagate; edges concatenated by hand.
@Test void float8IntegerConversionRanges() throws Exception {
  for (double v : new double[]{0.0, 1.9, -1.9, (double) Integer.MAX_VALUE, /*…*/}) { …assert… }
  for (double v : new double[]{1e18, Double.POSITIVE_INFINITY, /*…*/})
    assertThrows(PSQLException.class, () -> Float8Codec.INSTANCE.decodeAsInt(encode(v), …));
}

// jetCheck: one property over the whole domain; Predicate can't throw checked, so wrap;
// edges mixed in with anyOf(doubles(), sampledFrom(...)).
@Test void decodeAsIntContract() { check(DOUBLES, Float8JetCheckTest::intContract); }
private static boolean intContract(double v) { return ok(() -> {
  byte[] wire = Float8Codec.INSTANCE.encodeBinary(v, FLOAT8, CTX);
  if (Double.isInfinite(v) || v < Integer.MIN_VALUE || v > Integer.MAX_VALUE)
    assertThrows(PSQLException.class, () -> Float8Codec.INSTANCE.decodeAsInt(wire, FLOAT8, CTX));
  else if (Double.isNaN(v)) assertEquals(0, Float8Codec.INSTANCE.decodeAsInt(wire, FLOAT8, CTX));
  else assertEquals((int) v, Float8Codec.INSTANCE.decodeAsInt(wire, FLOAT8, CTX));
}); }
```

## Tradeoffs (what actually differed when writing them)

| dimension | Instancio | jetCheck |
|---|---|---|
| **Shrinking** | none — needed a hand-written `Minimizer` (~70 LOC) + its own test | **built in**: `getBreakingValue()` returns the minimized example. Reduced a random failing list to a *single element* automatically… |
| …shrinking quality | the manual `Minimizer` reduces to the exact boundary | …but **not always optimal**: it left the value at `[252]`, not the boundary `[200]`. Helpful, not perfect |
| **`forAll` ergonomics** | plain `@Test` loop → checked `SQLException` propagates naturally | property is a `Predicate` → **cannot throw checked exceptions**, so codec `SQLException` must be wrapped |
| **Specials (NaN/±Inf)** | random generation does *not* emit them — add explicitly | `Generator.doubles()` **includes** NaN/±Inf |
| **Boundary edges** | concatenate an explicit edge `List` with the random batch | mix via `anyOf(doubles(), sampledFrom(edges…))`. *Both* need explicit edges — neither finds `-0.0`/MIN/MAX by luck |
| **Determinism / seed** | `@Seed` / `Settings.SEED` is **first-class & encouraged** | `withSeed` is **`@Deprecated`**; jetCheck is built to use fresh randomness each run and reproduce via a printed `rechecking(...)` token. Pinning it (needed for PIT + stable CI) fights the design |
| **PIT compatibility** | runs via `InstancioExtension` (JUnit 5) — PIT sees a normal test | framework-agnostic: just call `PropertyChecker` in a `@Test`, no engine/extension — PIT sees a normal test. Both fine |
| **CI stability** | deterministic by construction | default fresh-random ⇒ a property can pass for months then fail on a new input (good for finding bugs, awkward for a green-required gate) unless you pin the (deprecated) seed |
| **Best at** | generating **complex populated objects** (records/beans/collections) | **fuzzing value spaces** with shrinking + **stateful** command sequences (`checkScenarios`) |
| **Maintenance** | active — 5.6.0 (May 2026), ~monthly, Tidelift backing | sleepy — **0.2.3**, JetBrains-internal (IntelliJ), infrequent releases |
| **License / Java** | Apache-2.0, Java 8+ | Apache-2.0, Java 8+ |

## Shrinking, concretely

This is jetCheck's headline advantage and the user's main concern (variable-length
counterexamples). `ByteaJetCheckTest.jetCheckShrinksAutomatically` falsifies a deliberately broken
property and gets the minimized example for free:

```
failed on iteration 7 (use recheckingIteration(...,7) or withSeed(...) to reproduce), shrinking...
=> getBreakingValue() == [252]      // shrunk a ~random list down to ONE element, automatically
```

The Instancio side reproduces this by hand: `Minimizer.shrink(failing, stillFails)` (chunk + element
delta-debugging) plus `MinimizerTest` to prove the minimizer works. For one codec that is a minor
cost; across many variable-length codecs (arrays, composites, ranges) the built-in shrinker is the
real ergonomic win — tempered by the fact that jetCheck's shrink was *good, not optimal* here.

## Recommendation for pgjdbc

The mutation numbers do **not** decide this — both clear the bar. Decide on the axes that differ:

- **Pick jetCheck if** you want a genuine PBT tool: automatic shrinking and `checkScenarios`
  (stateful) will pay off for the variable-length/array/composite walkers, and the framework-agnostic
  `PropertyChecker`-in-a-`@Test` model needs no extension. Accept that you are taking on a
  **sleepy, effectively single-source dependency** (0.2.3) that you may end up vendoring, that its
  **seed API is deprecated** so deterministic CI/PIT runs use it against the grain, and that
  `Predicate` properties can't throw checked exceptions.

- **Pick Instancio if** you weight **active maintenance** and **deterministic-by-default** seeding
  (cleaner for a required-green CI gate and for stable PIT scores), and expect to generate **complex
  domain objects** later. Accept that it is **not** a PBT framework: no shrinking (hand-write a
  `Minimizer`), no `forAll`/stateful model, and you supply the NaN/Inf/boundary edges yourself.

A middle path also exists: a tiny framework-agnostic core (jetCheck *or* vavr-test) **vendored** for
the shrinker, used from plain JUnit 5 `@Test`s — smaller surface than adopting either as a
first-class dependency. For a wire-format codec layer that is mostly primitives + `byte[]` + bounded
collections, jetCheck's model is the more natural fit; the deciding question is whether the team is
comfortable depending on (and possibly maintaining) it.

## Reproduce

```bash
./gradlew :pgjdbc-instancio-test:test --tests '*JetCheck*'   # the jetCheck prototypes

# Float8Codec, each library's property test alone (fair, same surface):
P=org.postgresql.jdbc.codec; T=org.postgresql.test.codec
for pair in "instancio:$T.FloatConversionPropertyTest" "jetcheck:$T.Float8JetCheckTest"; do
  ./gradlew :postgresql:pitestCodec --init-script config/mutation/pitest.init.gradle \
    -Ppitest.reportDir=pitest-float-${pair%%:*} -Ppitest.targetClasses=$P.Float8Codec \
    -Ppitest.targetTests=${pair#*:} -Ppitest.extraTestProjects=:pgjdbc-instancio-test \
    -Ppitest.extraClasspathDeps=org.instancio:instancio-junit:5.6.0,org.jetbrains:jetCheck:0.2.3
done
python3 config/mutation/compare_runs.py \
    pgjdbc/build/reports/pitest-float-instancio/mutations.xml \
    pgjdbc/build/reports/pitest-float-jetcheck/mutations.xml instancio% jetcheck%
```
