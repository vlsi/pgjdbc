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
import org.postgresql.jdbc.codec.Float4Codec;
import org.postgresql.jdbc.codec.Float8Codec;
import org.postgresql.jdbc.codec.Int4Codec;
import org.postgresql.jdbc.codec.Int8Codec;
import org.postgresql.jdbc.codec.NumericCodec;
import org.postgresql.util.PSQLException;

import org.instancio.Instancio;
import org.instancio.junit.InstancioExtension;
import org.instancio.junit.Seed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Property tests for the scalar numeric codecs. Each test feeds a fixed set of boundary cases plus
 * a batch of Instancio-generated random values through the codec's own
 * {@code decodeBinary(encodeBinary(x))} oracle. The seed is pinned with {@link Seed} so PIT reruns
 * (which execute every test many times, once per mutant) stay deterministic.
 *
 * <p>These target the worst-covered scalar codecs from the mutation report — {@code NumericCodec}
 * (16%), {@code Float8Codec}/{@code Float4Codec} (16–17%) — whose surviving mutants were
 * unasserted NaN/Infinity branches and overflow boundaries.</p>
 */
@ExtendWith(InstancioExtension.class)
class NumericFamilyPropertyTest {

  private static final long SEED = 20260625L;
  private static final int RANDOM_CASES = 200;

  // ---------------------------------- int4 ----------------------------------

  @Test
  @Seed(SEED)
  void int4BinaryRoundtrips() throws Exception {
    PgType t = type("int4", "integer", Oid.INT4);
    List<Integer> values = new ArrayList<>(
        List.of(0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE));
    values.addAll(Instancio.ofList(Integer.class).size(RANDOM_CASES).create());
    for (Integer v : values) {
      byte[] wire = Int4Codec.INSTANCE.encodeBinary(v, t, CTX);
      assertEquals(v, Int4Codec.INSTANCE.decodeBinary(wire, t, CTX),
          () -> "int4 roundtrip failed for " + v);
    }
  }

  // ---------------------------------- int8 ----------------------------------

  @Test
  @Seed(SEED)
  void int8BinaryRoundtrips() throws Exception {
    PgType t = type("int8", "bigint", Oid.INT8);
    List<Long> values = new ArrayList<>(
        List.of(0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE));
    values.addAll(Instancio.ofList(Long.class).size(RANDOM_CASES).create());
    for (Long v : values) {
      byte[] wire = Int8Codec.INSTANCE.encodeBinary(v, t, CTX);
      assertEquals(v, Int8Codec.INSTANCE.decodeBinary(wire, t, CTX),
          () -> "int8 roundtrip failed for " + v);
    }
  }

  /**
   * {@code decodeAsInt} must reject out-of-int-range values and accept in-range ones. This pins the
   * overflow boundary conditionals in {@code Int8Codec.decodeAsInt} that survived mutation.
   */
  @Test
  @Seed(SEED)
  void int8DecodeAsIntHonoursIntRange() throws Exception {
    PgType t = type("int8", "bigint", Oid.INT8);

    List<Long> outOfRange = new ArrayList<>(List.of(
        Integer.MAX_VALUE + 1L, Integer.MIN_VALUE - 1L, Long.MAX_VALUE, Long.MIN_VALUE));
    for (Long v : outOfRange) {
      byte[] wire = Int8Codec.INSTANCE.encodeBinary(v, t, CTX);
      assertThrows(PSQLException.class, () -> Int8Codec.INSTANCE.decodeAsInt(wire, t, CTX),
          () -> "expected overflow for " + v);
    }

    List<Long> inRange = new ArrayList<>(List.of(
        0L, 1L, -1L, (long) Integer.MAX_VALUE, (long) Integer.MIN_VALUE));
    for (Long v : inRange) {
      byte[] wire = Int8Codec.INSTANCE.encodeBinary(v, t, CTX);
      assertEquals(v.intValue(), Int8Codec.INSTANCE.decodeAsInt(wire, t, CTX),
          () -> "int decode failed for " + v);
    }
  }

  // --------------------------------- float8 ---------------------------------

  @Test
  @Seed(SEED)
  void float8BinaryRoundtripsIncludingSpecials() throws Exception {
    PgType t = type("float8", "double precision", Oid.FLOAT8);
    List<Double> values = new ArrayList<>(List.of(
        0.0, -0.0, 1.0, -1.0, Double.MIN_VALUE, Double.MAX_VALUE,
        Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY));
    values.addAll(Instancio.ofList(Double.class).size(RANDOM_CASES).create());
    for (Double v : values) {
      byte[] wire = Float8Codec.INSTANCE.encodeBinary(v, t, CTX);
      assertEquals(v, Float8Codec.INSTANCE.decodeBinary(wire, t, CTX),
          () -> "float8 roundtrip failed for " + v);
    }
  }

  // --------------------------------- float4 ---------------------------------

  @Test
  @Seed(SEED)
  void float4BinaryRoundtripsIncludingSpecials() throws Exception {
    PgType t = type("float4", "real", Oid.FLOAT4);
    List<Float> values = new ArrayList<>(List.of(
        0.0f, -0.0f, 1.0f, -1.0f, Float.MIN_VALUE, Float.MAX_VALUE,
        Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY));
    values.addAll(Instancio.ofList(Float.class).size(RANDOM_CASES).create());
    for (Float v : values) {
      byte[] wire = Float4Codec.INSTANCE.encodeBinary(v, t, CTX);
      assertEquals(v, Float4Codec.INSTANCE.decodeBinary(wire, t, CTX),
          () -> "float4 roundtrip failed for " + v);
    }
  }

  // --------------------------------- numeric --------------------------------

  @Test
  @Seed(SEED)
  void numericBinaryRoundtripsFiniteValues() throws Exception {
    PgType t = type("numeric", "numeric", Oid.NUMERIC);
    List<BigDecimal> values = new ArrayList<>(List.of(
        BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.TEN,
        new BigDecimal("-1"), new BigDecimal("0.00"), new BigDecimal("123.456"),
        new BigDecimal("-9999999999.999999"), new BigDecimal("1E+10")));
    values.addAll(Instancio.ofList(BigDecimal.class).size(RANDOM_CASES).create());
    for (BigDecimal v : values) {
      byte[] wire = NumericCodec.INSTANCE.encodeBinary(v, t, CTX);
      Object back = NumericCodec.INSTANCE.decodeBinary(wire, t, CTX);
      // PostgreSQL numeric preserves value but may normalise scale, so compare by value.
      assertEquals(0, ((BigDecimal) back).compareTo(v),
          () -> "numeric roundtrip failed for " + v + " (got " + back + ")");
    }
  }

  /**
   * PostgreSQL {@code numeric} carries NaN as a special header that {@code BigDecimal} cannot
   * represent, so the codec surfaces it as {@link Double#NaN}. This pins the {@code instanceof
   * Double}/{@code isNaN} branches in {@code NumericCodec.decodeBinary} that survived mutation.
   */
  @Test
  void numericDecodesNanSentinel() throws Exception {
    PgType t = type("numeric", "numeric", Oid.NUMERIC);
    // wire: int16 ndigits=0, int16 weight=0, uint16 sign=0xC000 (NUMERIC_NAN), int16 dscale=0
    byte[] nan = {0, 0, 0, 0, (byte) 0xC0, 0, 0, 0};
    assertEquals(Double.NaN, NumericCodec.INSTANCE.decodeBinary(nan, t, CTX));
  }
}
