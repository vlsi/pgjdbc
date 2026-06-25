/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.postgresql.test.codec.CodecTestSupport.NO_CTX;
import static org.postgresql.test.codec.CodecTestSupport.type;

import org.postgresql.core.Oid;
import org.postgresql.jdbc.PgType;
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
 * Deep property test for {@link NumericCodec}, aimed at the branches the plain binary roundtrip
 * never reaches: {@code decodeText} and the {@code decodeAs*} conversion methods, across finite,
 * NaN/Infinity, and overflow inputs. These are exactly the surviving/uncovered mutants from the
 * report, and they are all context-free so a {@code null} context is sufficient.
 *
 * <p>This is the lesson from the first measurement: a naive {@code decode(encode(x))} roundtrip
 * mostly re-covers what the existing unit tests already hit; to actually move the mutation score
 * the properties have to exercise the specific surviving branches.</p>
 */
@ExtendWith(InstancioExtension.class)
class NumericConversionPropertyTest {

  private static final long SEED = 20260625L;
  private static final int RANDOM_CASES = 200;
  private static final PgType NUMERIC = type("numeric", "numeric", Oid.NUMERIC);

  // NaN / ±Infinity in PostgreSQL numeric binary: int16 ndigits=0, int16 weight=0,
  // uint16 sign (NaN=0xC000, +Inf=0xD000, -Inf=0xF000), int16 dscale=0.
  private static final byte[] NAN_BIN = {0, 0, 0, 0, (byte) 0xC0, 0, 0, 0};
  private static final byte[] POS_INF_BIN = {0, 0, 0, 0, (byte) 0xD0, 0, 0, 0};
  private static final byte[] NEG_INF_BIN = {0, 0, 0, 0, (byte) 0xF0, 0, 0, 0};

  /** All conversion methods must agree with the value, for finite inputs, in both formats. */
  @Test
  @Seed(SEED)
  void finiteConversionsAgree() throws Exception {
    List<BigDecimal> values = new ArrayList<>(List.of(
        BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("-1"),
        new BigDecimal("0.00"), new BigDecimal("123.456"), new BigDecimal("-0.5")));
    values.addAll(Instancio.ofList(BigDecimal.class).size(RANDOM_CASES).create());

    for (BigDecimal v : values) {
      byte[] wire = NumericCodec.INSTANCE.encodeBinary(v, NUMERIC, NO_CTX);
      String text = v.toPlainString();

      // binary
      assertEquals(0, NumericCodec.INSTANCE.decodeAsBigDecimal(wire, NUMERIC, NO_CTX).compareTo(v),
          () -> "decodeAsBigDecimal(byte[]) for " + v);
      assertEquals(v.doubleValue(), NumericCodec.INSTANCE.decodeAsDouble(wire, NUMERIC, NO_CTX),
          () -> "decodeAsDouble(byte[]) for " + v);
      assertEquals((float) v.doubleValue(), NumericCodec.INSTANCE.decodeAsFloat(wire, NUMERIC, NO_CTX),
          () -> "decodeAsFloat(byte[]) for " + v);

      // text
      assertEquals(0, ((BigDecimal) NumericCodec.INSTANCE.decodeText(text, NUMERIC, NO_CTX)).compareTo(v),
          () -> "decodeText for " + v);
      assertEquals(0, NumericCodec.INSTANCE.decodeAsBigDecimal(text, NUMERIC, NO_CTX).compareTo(v),
          () -> "decodeAsBigDecimal(String) for " + v);
      assertEquals(v.doubleValue(), NumericCodec.INSTANCE.decodeAsDouble(text, NUMERIC, NO_CTX),
          () -> "decodeAsDouble(String) for " + v);
    }
  }

  /** NaN / ±Infinity handling differs per target type; pin every branch in both formats. */
  @Test
  void nanAndInfinity() throws Exception {
    // decodeText / decodeBinary surface specials as Double sentinels.
    assertEquals(Double.NaN, NumericCodec.INSTANCE.decodeText("NaN", NUMERIC, NO_CTX));
    assertEquals(Double.POSITIVE_INFINITY, NumericCodec.INSTANCE.decodeText("Infinity", NUMERIC, NO_CTX));
    assertEquals(Double.POSITIVE_INFINITY, NumericCodec.INSTANCE.decodeText("+Infinity", NUMERIC, NO_CTX));
    assertEquals(Double.NEGATIVE_INFINITY, NumericCodec.INSTANCE.decodeText("-Infinity", NUMERIC, NO_CTX));
    assertEquals(Double.NaN, NumericCodec.INSTANCE.decodeBinary(NAN_BIN, NUMERIC, NO_CTX));

    // decodeAsDouble keeps the sentinels, in both formats.
    assertEquals(Double.NaN, NumericCodec.INSTANCE.decodeAsDouble("NaN", NUMERIC, NO_CTX));
    assertEquals(Double.POSITIVE_INFINITY, NumericCodec.INSTANCE.decodeAsDouble("Infinity", NUMERIC, NO_CTX));
    assertEquals(Double.NEGATIVE_INFINITY, NumericCodec.INSTANCE.decodeAsDouble("-Infinity", NUMERIC, NO_CTX));
    assertEquals(Double.NaN, NumericCodec.INSTANCE.decodeAsDouble(NAN_BIN, NUMERIC, NO_CTX));
    assertEquals(Double.POSITIVE_INFINITY, NumericCodec.INSTANCE.decodeAsDouble(POS_INF_BIN, NUMERIC, NO_CTX));
    assertEquals(Double.NEGATIVE_INFINITY, NumericCodec.INSTANCE.decodeAsDouble(NEG_INF_BIN, NUMERIC, NO_CTX));

    // BigDecimal cannot represent the specials, so those conversions must throw, in both formats.
    for (String s : List.of("NaN", "Infinity", "+Infinity", "-Infinity")) {
      assertThrows(PSQLException.class,
          () -> NumericCodec.INSTANCE.decodeAsBigDecimal(s, NUMERIC, NO_CTX),
          () -> "decodeAsBigDecimal(String) should reject " + s);
    }
    for (byte[] b : List.of(NAN_BIN, POS_INF_BIN, NEG_INF_BIN)) {
      assertThrows(PSQLException.class,
          () -> NumericCodec.INSTANCE.decodeAsBigDecimal(b, NUMERIC, NO_CTX),
          "decodeAsBigDecimal(byte[]) should reject specials");
    }
  }

  /** Integer conversions truncate in range and throw out of range. */
  @Test
  void integerConversionRanges() throws Exception {
    // in range
    assertEquals(42, NumericCodec.INSTANCE.decodeAsInt(
        NumericCodec.INSTANCE.encodeBinary(new BigDecimal("42.9"), NUMERIC, NO_CTX), NUMERIC, NO_CTX));
    assertEquals(42L, NumericCodec.INSTANCE.decodeAsLong("42.9", NUMERIC, NO_CTX));

    // out of int range -> decodeAsInt throws, decodeAsLong still fine
    byte[] big = NumericCodec.INSTANCE.encodeBinary(
        new BigDecimal(Integer.MAX_VALUE + 10L), NUMERIC, NO_CTX);
    assertThrows(PSQLException.class, () -> NumericCodec.INSTANCE.decodeAsInt(big, NUMERIC, NO_CTX));
    assertEquals(Integer.MAX_VALUE + 10L, NumericCodec.INSTANCE.decodeAsLong(big, NUMERIC, NO_CTX));

    // out of long range -> decodeAsLong throws
    assertThrows(PSQLException.class,
        () -> NumericCodec.INSTANCE.decodeAsLong("99999999999999999999999", NUMERIC, NO_CTX));
  }
}
