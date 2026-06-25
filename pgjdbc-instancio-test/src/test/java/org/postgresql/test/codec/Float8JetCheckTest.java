/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.postgresql.test.codec.CodecTestSupport.CTX;
import static org.postgresql.test.codec.CodecTestSupport.type;

import org.postgresql.core.Oid;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.codec.Float8Codec;
import org.postgresql.util.PSQLException;

import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.PropertyChecker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.function.Predicate;

/**
 * jetCheck counterpart to the Float8 half of {@link FloatConversionPropertyTest}, written to
 * compare jetCheck and Instancio head-to-head on the <em>same surface</em>. See
 * {@code config/mutation/INSTANCIO_VS_JETCHECK.md}.
 *
 * <p>Notes that fall out of writing it this way:</p>
 * <ul>
 *   <li>The property is a {@link Predicate} — it cannot throw checked exceptions, so the codec's
 *       {@code SQLException} has to be wrapped (the Instancio tests are plain {@code throws}
 *       loops).</li>
 *   <li>Pure {@code Generator.doubles()} misses exact boundaries (−0.0, MIN/MAX, the int/long
 *       limits), so — exactly like the Instancio test concatenates an edge list — we mix edges in
 *       with {@code anyOf(doubles(), sampledFrom(...))}. Both frameworks need explicit edges for
 *       good boundary coverage; neither finds them by luck.</li>
 * </ul>
 */
class Float8JetCheckTest {

  private static final long SEED = 20260625L;
  private static final int ITERS = 200;
  private static final PgType FLOAT8 = type("float8", "double precision", Oid.FLOAT8);

  // Random doubles (which include NaN/±Inf) mixed with the exact boundaries the mutants live on.
  private static final Generator<Double> DOUBLES = Generator.anyOf(
      Generator.doubles(),
      Generator.sampledFrom(
          0.0, -0.0, 1.0, -1.0, 3.14, Double.MIN_VALUE, Double.MAX_VALUE,
          Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
          (double) Integer.MAX_VALUE, (double) Integer.MIN_VALUE,
          (double) Long.MAX_VALUE, (double) Long.MIN_VALUE,
          1e18, -1e18, 1e300, -1e300));

  /**
   * Runs {@code property} over {@code generator} with a pinned seed and iteration count.
   * jetCheck's {@code withSeed} is {@code @Deprecated} (it is designed to use fresh randomness on
   * every run and reproduce failures from a printed seed); we pin it so the suite is reproducible
   * in CI and — crucially — stable under PIT, which reruns each test once per mutant.
   */
  @SuppressWarnings("deprecation")
  private static <T> void check(Generator<T> generator, Predicate<T> property) {
    PropertyChecker.customized().withSeed(SEED).withIterationCount(ITERS).forAll(generator, property);
  }

  @Test
  void decodeAsDoubleRoundtrips() {
    check(DOUBLES, Float8JetCheckTest::roundtrips);
  }

  @Test
  void decodeAsDoubleParsesText() {
    check(DOUBLES, Float8JetCheckTest::textRoundtrips);
  }

  @Test
  void decodeAsIntContract() {
    check(DOUBLES, Float8JetCheckTest::intContract);
  }

  @Test
  void decodeAsLongContract() {
    check(DOUBLES, Float8JetCheckTest::longContract);
  }

  @Test
  void decodeAsBigDecimalRejectsNonFinite() {
    check(DOUBLES, Float8JetCheckTest::bigDecimalContract);
  }

  // --- properties expressed as named predicates (checked exceptions wrapped) ---

  private static boolean roundtrips(double v) {
    return ok(() -> {
      byte[] wire = Float8Codec.INSTANCE.encodeBinary(v, FLOAT8, CTX);
      assertEquals(v, Float8Codec.INSTANCE.decodeAsDouble(wire, FLOAT8, CTX));
      assertEquals((float) v, Float8Codec.INSTANCE.decodeAsFloat(wire, FLOAT8, CTX));
    });
  }

  private static boolean textRoundtrips(double v) {
    return ok(() ->
        assertEquals(v, Float8Codec.INSTANCE.decodeAsDouble(Double.toString(v), FLOAT8, CTX)));
  }

  private static boolean intContract(double v) {
    return ok(() -> {
      byte[] wire = Float8Codec.INSTANCE.encodeBinary(v, FLOAT8, CTX);
      if (Double.isInfinite(v) || v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
        assertThrows(PSQLException.class, () -> Float8Codec.INSTANCE.decodeAsInt(wire, FLOAT8, CTX));
      } else if (Double.isNaN(v)) {
        // NaN fails both range comparisons, so the cast yields 0 (current contract).
        assertEquals(0, Float8Codec.INSTANCE.decodeAsInt(wire, FLOAT8, CTX));
      } else {
        assertEquals((int) v, Float8Codec.INSTANCE.decodeAsInt(wire, FLOAT8, CTX));
      }
    });
  }

  private static boolean longContract(double v) {
    return ok(() -> {
      byte[] wire = Float8Codec.INSTANCE.encodeBinary(v, FLOAT8, CTX);
      if (Double.isInfinite(v) || v < (double) Long.MIN_VALUE || v > (double) Long.MAX_VALUE) {
        assertThrows(PSQLException.class, () -> Float8Codec.INSTANCE.decodeAsLong(wire, FLOAT8, CTX));
      } else if (Double.isNaN(v)) {
        assertEquals(0L, Float8Codec.INSTANCE.decodeAsLong(wire, FLOAT8, CTX));
      } else {
        assertEquals((long) v, Float8Codec.INSTANCE.decodeAsLong(wire, FLOAT8, CTX));
      }
    });
  }

  private static boolean bigDecimalContract(double v) {
    return ok(() -> {
      byte[] wire = Float8Codec.INSTANCE.encodeBinary(v, FLOAT8, CTX);
      if (Double.isNaN(v) || Double.isInfinite(v)) {
        assertThrows(PSQLException.class,
            () -> Float8Codec.INSTANCE.decodeAsBigDecimal(wire, FLOAT8, CTX));
      } else {
        assertEquals(0, Float8Codec.INSTANCE.decodeAsBigDecimal(wire, FLOAT8, CTX)
            .compareTo(BigDecimal.valueOf(v)));
      }
    });
  }

  @FunctionalInterface
  private interface SqlBody {
    void run() throws SQLException;
  }

  private static boolean ok(SqlBody body) {
    try {
      body.run();
      return true;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }
}
