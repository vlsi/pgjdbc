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
 * Targets the {@code decodeAs*} conversion branches of {@link Float8Codec} / {@link Float4Codec}
 * — the overflow guards and NaN/Infinity handling that survived mutation (both codecs were ~16 %).
 * Context-free, so the shared connectionless {@code CTX} is used.
 */
@ExtendWith(InstancioExtension.class)
class FloatConversionPropertyTest {

  private static final long SEED = 20260625L;
  private static final int RANDOM_CASES = 200;
  private static final PgType FLOAT8 = type("float8", "double precision", Oid.FLOAT8);
  private static final PgType FLOAT4 = type("float4", "real", Oid.FLOAT4);

  // ---------------------------------- float8 ----------------------------------

  @Test
  @Seed(SEED)
  void float8DecodeAsDoubleAgrees() throws Exception {
    List<Double> values = new ArrayList<>(List.of(
        0.0, -0.0, 1.5, -1.5, Double.MIN_VALUE, Double.MAX_VALUE,
        Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY));
    values.addAll(Instancio.ofList(Double.class).size(RANDOM_CASES).create());
    for (Double v : values) {
      byte[] wire = Float8Codec.INSTANCE.encodeBinary(v, FLOAT8, CTX);
      assertEquals(v, Float8Codec.INSTANCE.decodeAsDouble(wire, FLOAT8, CTX),
          () -> "decodeAsDouble for " + v);
      assertEquals(v.floatValue(), Float8Codec.INSTANCE.decodeAsFloat(wire, FLOAT8, CTX),
          () -> "decodeAsFloat for " + v);
    }
  }

  @Test
  void float8IntegerConversionRanges() throws Exception {
    // in range: truncates toward zero
    for (double v : new double[]{0.0, 1.9, -1.9, 100.0,
        (double) Integer.MAX_VALUE, (double) Integer.MIN_VALUE}) {
      byte[] wire = Float8Codec.INSTANCE.encodeBinary(v, FLOAT8, CTX);
      assertEquals((int) v, Float8Codec.INSTANCE.decodeAsInt(wire, FLOAT8, CTX), "int " + v);
      assertEquals((long) v, Float8Codec.INSTANCE.decodeAsLong(wire, FLOAT8, CTX), "long " + v);
    }
    // out of range / non-finite -> throws
    for (double v : new double[]{1e18, -1e18, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      byte[] wire = Float8Codec.INSTANCE.encodeBinary(v, FLOAT8, CTX);
      assertThrows(PSQLException.class, () -> Float8Codec.INSTANCE.decodeAsInt(wire, FLOAT8, CTX),
          () -> "decodeAsInt should reject " + v);
    }
    for (double v : new double[]{1e300, -1e300, Double.POSITIVE_INFINITY}) {
      byte[] wire = Float8Codec.INSTANCE.encodeBinary(v, FLOAT8, CTX);
      assertThrows(PSQLException.class, () -> Float8Codec.INSTANCE.decodeAsLong(wire, FLOAT8, CTX),
          () -> "decodeAsLong should reject " + v);
    }
  }

  @Test
  void float8BigDecimalRejectsNonFinite() throws Exception {
    byte[] finite = Float8Codec.INSTANCE.encodeBinary(3.5, FLOAT8, CTX);
    assertEquals(0, Float8Codec.INSTANCE.decodeAsBigDecimal(finite, FLOAT8, CTX)
        .compareTo(BigDecimal.valueOf(3.5)));
    for (double v : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      byte[] wire = Float8Codec.INSTANCE.encodeBinary(v, FLOAT8, CTX);
      assertThrows(PSQLException.class,
          () -> Float8Codec.INSTANCE.decodeAsBigDecimal(wire, FLOAT8, CTX),
          () -> "decodeAsBigDecimal should reject " + v);
    }
  }

  @Test
  void float8TextParsing() throws Exception {
    assertEquals(3.14, Float8Codec.INSTANCE.decodeAsDouble("3.14", FLOAT8, CTX));
    assertEquals(Double.POSITIVE_INFINITY, Float8Codec.INSTANCE.decodeAsDouble("Infinity", FLOAT8, CTX));
    assertThrows(PSQLException.class, () -> Float8Codec.INSTANCE.decodeAsDouble("not-a-number", FLOAT8, CTX));
  }

  // ---------------------------------- float4 ----------------------------------

  @Test
  @Seed(SEED)
  void float4DecodeAsFloatAgrees() throws Exception {
    List<Float> values = new ArrayList<>(List.of(
        0.0f, -0.0f, 1.5f, -1.5f, Float.MIN_VALUE, Float.MAX_VALUE,
        Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY));
    values.addAll(Instancio.ofList(Float.class).size(RANDOM_CASES).create());
    for (Float v : values) {
      byte[] wire = Float4Codec.INSTANCE.encodeBinary(v, FLOAT4, CTX);
      assertEquals(v, Float4Codec.INSTANCE.decodeAsFloat(wire, FLOAT4, CTX),
          () -> "decodeAsFloat for " + v);
    }
  }

  @Test
  void float4IntegerAndBigDecimalGuards() throws Exception {
    // in range
    byte[] hundred = Float4Codec.INSTANCE.encodeBinary(100.0f, FLOAT4, CTX);
    assertEquals(100, Float4Codec.INSTANCE.decodeAsInt(hundred, FLOAT4, CTX));
    // overflow / non-finite -> throws
    for (float v : new float[]{1e18f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
      byte[] wire = Float4Codec.INSTANCE.encodeBinary(v, FLOAT4, CTX);
      assertThrows(PSQLException.class, () -> Float4Codec.INSTANCE.decodeAsInt(wire, FLOAT4, CTX),
          () -> "decodeAsInt should reject " + v);
    }
    for (float v : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
      byte[] wire = Float4Codec.INSTANCE.encodeBinary(v, FLOAT4, CTX);
      assertThrows(PSQLException.class,
          () -> Float4Codec.INSTANCE.decodeAsBigDecimal(wire, FLOAT4, CTX),
          () -> "decodeAsBigDecimal should reject " + v);
    }
  }
}
