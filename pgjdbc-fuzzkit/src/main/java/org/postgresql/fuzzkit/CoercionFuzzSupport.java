/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.PrefersJavaTime;
import org.postgresql.api.codec.TypeName;
import org.postgresql.api.codec.WireValueSlice;
import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.coercion.CoercionOutcome;
import org.postgresql.fuzzkit.coercion.NumericTypmod;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.jdbc.PgSQLInputBinary;
import org.postgresql.jdbc.PgSQLInputText;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.util.Map;
import java.util.TimeZone;

/**
 * Drives one {@link CoercionCase} through the SQLData read adapters and asserts the read outcome via
 * {@link ReadOracle} against the {@code ReadCoercions} registry. The value reaches the reader on the
 * canonical wire -- the field's own codec -- so the read side is the single axis under test here.
 *
 * <p>The reader fuzzer stays on the canonical wire alone. The driver write paths (the typed
 * {@code PgSQLOutput} method, the generic {@code writeObject}) present field bytes identical to the
 * canonical codec on the diagonal, so they add no unique read coverage; the config dependence lives in
 * the field decoder over those bytes, and off-diagonal write&rarr;read stays in the round-trip fuzzer.
 * The byte-equivalence assumption is pinned by {@code TypedWriteMatchesCanonicalWireTest}.
 *
 * <p>The remaining dimensions: a {@link org.postgresql.fuzzkit.coercion.ScalarDescriptor} maps the field
 * type to its OID; {@link SqlInputReader} binds each {@code SQLInput} call to the
 * {@code ReadCoercions.Accessor} whose outcome it checks; and the read axes ({@code readObject} target
 * classes, {@code prefersJavaTime} config) and the outcome check live in {@link ReadOracle}, shared
 * with {@link CoercionRoundTripSupport}.
 */
public final class CoercionFuzzSupport {

  private CoercionFuzzSupport() {
  }

  private static PgType scalar(int oid) {
    return new PgType(TypeName.of("pg_catalog", "t" + oid), "t" + oid, oid, 'b', 'N', -1, 0, 0, 0);
  }

  public static void run(CoercionCase c) throws SQLException {
    int oid = c.kind.oid();
    // The field carries the case's applied modifier, so the reader resolves a modifier-sensitive
    // attribute (numeric(p,s)) to its declared scale; -1 leaves the field un-modified.
    PgType comp = FuzzComposites.singleField(oid, c.appliedTypmod);
    PrefersJavaTime p = c.prefersJavaTime;
    Map<String, String> config = ReadOracle.configFor(p);
    PgCodecContext ctx = (PgCodecContext) OfflineCodecContexts.offlineBuilder()
        .type(comp)
        .clientTimeZone(TimeZone.getDefault())
        .prefersJavaTime(p)
        .build();

    // The target class is only meaningful for readObject(Class); other readers ignore it, so a null
    // targetClass maps to a harmless placeholder.
    Class<?> target = c.targetClass == null ? Object.class : c.targetClass;
    // The registry outcome is format-independent, so it is looked up once for the whole matrix cell.
    @Nullable CoercionOutcome expected = ReadOracle.expected(oid, c.reader, target, config);

    for (Format format : Format.values()) {
      SQLInput in = openReader(c, oid, comp, ctx, format);
      ReadOracle.ReadResult result = ReadOracle.verify(in, c.reader, target, expected, format, c);
      assertDeclaredScaleValue(c, format, result);
    }
  }

  private static final BigDecimal INT_MIN = BigDecimal.valueOf(Integer.MIN_VALUE);
  private static final BigDecimal INT_MAX = BigDecimal.valueOf(Integer.MAX_VALUE);
  private static final BigDecimal LONG_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
  private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);

  /**
   * Value pin for the modifier-stamped numeric cells. {@link ReadOracle#verify} is value-blind -- a
   * reader that returns the raw wire value instead of the declared-scale one still counts as
   * "returned" -- but for {@code numeric(p,s)} the declared-scale value is predictable from the
   * written value alone. So {@code readBigDecimal} must return exactly it (value and scale), and
   * {@code readInt}/{@code readLong} its {@code numeric->int4/int8} cast, refusing exactly when that
   * cast overflows. Running through the {@code SQLInput} adapters, this also guards the descriptor
   * plumbing: a reader that stops stamping the field modifier reverts to the wire-faithful value and
   * fails the pin.
   */
  private static void assertDeclaredScaleValue(CoercionCase c, Format format,
      ReadOracle.ReadResult result) {
    if (c.appliedTypmod == -1 || c.kind.oid() != Oid.NUMERIC || !(c.value instanceof BigDecimal)) {
      return;
    }
    BigDecimal declared = ((BigDecimal) c.value)
        .setScale(NumericTypmod.scaleOf(c.appliedTypmod), RoundingMode.HALF_EVEN);
    switch (c.reader) {
      case READ_BIG_DECIMAL:
        assertTrue(result.returned(),
            () -> "readBigDecimal refused a finite numeric(p,s) value on " + format + " " + c);
        assertEquals(declared, result.value(),
            () -> "readBigDecimal must return the declared-scale value on " + format + " " + c);
        break;
      case READ_INT:
        assertIntegerCast(c, format, result, declared, INT_MIN, INT_MAX, true);
        break;
      case READ_LONG:
        assertIntegerCast(c, format, result, declared, LONG_MIN, LONG_MAX, false);
        break;
      default:
        break;
    }
  }

  /**
   * Asserts a returned {@code readInt}/{@code readLong} equals the PostgreSQL cast of the
   * declared-scale value (round half away from zero, then range-check), and that a refusal happens
   * exactly when that cast overflows the target range.
   */
  private static void assertIntegerCast(CoercionCase c, Format format, ReadOracle.ReadResult result,
      BigDecimal declared, BigDecimal min, BigDecimal max, boolean toInt) {
    BigDecimal rounded = declared.setScale(0, RoundingMode.HALF_UP);
    boolean fits = rounded.compareTo(min) >= 0 && rounded.compareTo(max) <= 0;
    String reader = toInt ? "readInt" : "readLong";
    if (!result.returned()) {
      assertFalse(fits, () -> reader + " refused although the declared-scale value " + declared
          + " fits on " + format + " " + c);
      return;
    }
    assertTrue(fits, () -> reader + " returned although the declared-scale value " + declared
        + " overflows on " + format + " " + c);
    Object expected = toInt ? (Object) rounded.intValueExact() : (Object) rounded.longValueExact();
    assertEquals(expected, result.value(),
        () -> reader + " must return the cast of the declared-scale value " + declared
            + " on " + format + " " + c);
  }

  private static SQLInput openReader(CoercionCase c, int oid, PgType comp, PgCodecContext ctx,
      Format format) throws SQLException {
    // Server-realistic wire: the field's own codec, handed pre-split to the reader adapter.
    WireValueSlice field = Codecs.encode(c.value, scalar(oid), ctx, format);
    return format == Format.TEXT
        ? new PgSQLInputText(new String[]{field.asString(StandardCharsets.UTF_8)}, comp, ctx)
        : new PgSQLInputBinary(singleFieldComposite(oid, field.toByteArray()), comp, ctx);
  }

  /**
   * Wraps one pre-encoded field body in the binary composite wire the reader expects: {@code int4}
   * field count, then the field's {@code int4} OID, {@code int4} length, and body.
   */
  private static byte[] singleFieldComposite(int oid, byte[] body) {
    byte[] wire = new byte[12 + body.length];
    ByteConverter.int4(wire, 0, 1);
    ByteConverter.int4(wire, 4, oid);
    ByteConverter.int4(wire, 8, body.length);
    System.arraycopy(body, 0, wire, 12, body.length);
    return wire;
  }
}
