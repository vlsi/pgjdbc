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
import org.postgresql.api.codec.JavaTimePreferences;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Ref;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLXML;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
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
 * classes, {@code javaTimePreferences} config) and the outcome check live in {@link ReadOracle}, shared
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
    JavaTimePreferences p = c.javaTimePreferences;
    Map<String, String> config = ReadOracle.configFor(p);
    PgCodecContext ctx = (PgCodecContext) OfflineCodecContexts.offlineBuilder()
        .type(comp)
        .clientTimeZone(TimeZone.getDefault())
        .javaTimePreferences(p)
        .build();

    // The target class is only meaningful for readObject(Class); other readers ignore it, so a null
    // targetClass maps to a harmless placeholder.
    Class<?> target = c.targetClass == null ? Object.class : c.targetClass;
    // The registry outcome is format-independent, so it is looked up once for the whole matrix cell.
    @Nullable CoercionOutcome expected = ReadOracle.expected(oid, c.reader, target, config);

    Map<Format, ReadOracle.ReadResult> byFormat = new EnumMap<>(Format.class);
    for (Format format : Format.values()) {
      SQLInput in = openReader(c, oid, comp, ctx, format);
      ReadOracle.ReadResult result = ReadOracle.verify(in, c.reader, target, expected, format, c);
      assertDeclaredScaleValue(c, format, result);
      byFormat.put(format, result);
    }
    if (wiresCarryTheSameValue(c, oid, ctx)) {
      assertFormatParity(c, target, byFormat.get(Format.TEXT), byFormat.get(Format.BINARY));
    }
  }

  /**
   * Whether the two wires this case built represent the same value -- the precondition the parity oracle
   * rests on, checked by decoding each wire back through the type's own default decode.
   *
   * <p>It does not always hold, because {@link #openReader} builds each wire with the codec's own encoder
   * and the two encoders do not agree on a value the type cannot hold. A generator is free to hand an
   * {@code oid} case a {@code Long} above {@code uint32} or a {@code "char"} case a multi-character string;
   * the text encoder writes it out whole, the binary encoder truncates it to the width the wire has. The
   * server would reject the text literal, so neither wire is wrong to a reader -- they are simply two
   * different values, and comparing the reads would assert nothing about the reader. The write side owns
   * that asymmetry ({@code WriteCoercions}, {@code TypedWriteMatchesCanonicalWireTest}).
   */
  private static boolean wiresCarryTheSameValue(CoercionCase c, int oid, PgCodecContext ctx) {
    PgType scalar = scalar(oid);
    try {
      Object fromText = Codecs.decode(Codecs.encode(c.value, scalar, ctx, Format.TEXT), scalar, ctx,
          Object.class);
      Object fromBinary = Codecs.decode(Codecs.encode(c.value, scalar, ctx, Format.BINARY), scalar, ctx,
          Object.class);
      if (fromText instanceof BigDecimal && fromBinary instanceof BigDecimal) {
        return ((BigDecimal) fromText).compareTo((BigDecimal) fromBinary) == 0;
      }
      return Objects.deepEquals(fromText, fromBinary);
    } catch (SQLException oneWireIsUnusable) {
      // A value only one format can carry: there is no pair to compare, so there is no parity to assert.
      return false;
    }
  }

  /**
   * Value oracle over the format axis: one value, one reader, two wires. The wire format is a transport
   * choice the server makes, so a reader must not see the value change with it -- the two legs refuse
   * together, or return equal values.
   *
   * <p>This is what {@link ReadOracle#verify} alone cannot check. The registry states the contract-level
   * outcome (returns, or refuses with this {@code SQLState}) and is value-blind by design, so a reader that
   * returns the wrong value passes it. Parity needs no per-cell expected-value model to close that: the
   * driver's own other leg supplies the expectation, which is why it reaches every cell the matrix has
   * rather than the handful a hand-written table could cover. It is how a decoder that reads a composite
   * field from the wrong offset in the row buffer surfaces -- only the binary leg has a non-zero offset,
   * so the text leg is the control.
   */
  private static void assertFormatParity(CoercionCase c, Class<?> target,
      ReadOracle.@Nullable ReadResult text, ReadOracle.@Nullable ReadResult binary) {
    if (text == null || binary == null || isKnownFormatDivergence(c.kind.oid(), c.reader, target)) {
      return;
    }
    String reader = ReadOracle.describe(c.reader, target);
    if (text.returned() != binary.returned()) {
      throw new AssertionError(reader + " changed outcome with the wire format: text "
          + (text.returned() ? "returned" : "refused") + " but binary "
          + (binary.returned() ? "returned" : "refused") + " " + c);
    }
    if (!text.returned()) {
      return;
    }
    @Nullable Object textValue = parityForm(text.value());
    @Nullable Object binaryValue = parityForm(binary.value());
    if (textValue == OPAQUE || binaryValue == OPAQUE) {
      return;
    }
    if (!Objects.deepEquals(textValue, binaryValue)) {
      throw new AssertionError(reader + " changed value with the wire format: text -> "
          + describeParity(textValue) + " but binary -> " + describeParity(binaryValue) + " " + c);
    }
  }

  /**
   * Cells where the two wires legitimately disagree, so the parity oracle skips them: the string rendering
   * of a {@code timestamp} or a {@code timestamptz}.
   *
   * <p>This is an artefact of how the fuzzer builds its text wire, not a reader defect.
   * {@link #openReader} encodes the value with the codec's own {@code encodeText}, which is the
   * <em>bind</em> literal, and a bind literal for these two types carries a UTC offset -- one formatter
   * serves both, so a {@code timestamp} binds as {@code 1970-01-01 00:00:00+00}, and a
   * {@code timestamptz} binds in the value's own offset rather than the session zone the server renders
   * in. The server accepts either on a bind and sends back neither, so the text leg reads a literal no
   * such column produces, and its rendering keeps an offset the binary leg has no reason to invent. Both
   * legs still denote the same instant -- {@link #wiresCarryTheSameValue} confirms that before the parity
   * check runs -- so only the rendering diverges, and only for the readers that return one.
   *
   * <p>Closing this needs a text wire built the way the server writes one, which is a change to the
   * fuzzer's wire model rather than a parity exemption. Until then the binary leg carries the rendering
   * coverage for these two types.
   */
  private static boolean isKnownFormatDivergence(int oid, SqlInputReader reader, Class<?> target) {
    return (oid == Oid.TIMESTAMP || oid == Oid.TIMESTAMPTZ) && rendersToString(reader, target);
  }

  /**
   * Whether a reader hands back the value's string rendering rather than a typed value. The character
   * streams belong here with the two string readers: they deliver the same rendering, one chunk at a time.
   */
  private static boolean rendersToString(SqlInputReader reader, Class<?> target) {
    switch (reader) {
      case READ_STRING:
      case READ_NSTRING:
      case READ_CHARACTER_STREAM:
      case READ_ASCII_STREAM:
        return true;
      case READ_OBJECT_AS:
        return target == String.class;
      default:
        return false;
    }
  }

  /**
   * Marker for a read whose value the parity check cannot compare. A {@code Blob}, {@code Clob},
   * {@code Array}, {@code SQLXML} or {@code Ref} is a live handle onto the driver, with identity equality
   * and a lifecycle; comparing two of them would assert nothing, and draining them is a different oracle.
   * The outcome check still runs for these readers.
   */
  private static final Object OPAQUE = new Object();

  /**
   * The comparable form of a read value. A stream is drained -- the bytes or characters it yields are the
   * value, and they are what the format decides -- and a JDBC handle becomes {@link #OPAQUE}. Everything
   * else compares by {@code equals}.
   */
  private static @Nullable Object parityForm(@Nullable Object value) {
    if (value instanceof InputStream) {
      return drainBytes((InputStream) value);
    }
    if (value instanceof Reader) {
      return drainChars((Reader) value);
    }
    if (value instanceof Blob || value instanceof Clob || value instanceof Array
        || value instanceof SQLXML || value instanceof Ref) {
      return OPAQUE;
    }
    return value;
  }

  private static Object drainBytes(InputStream in) {
    try (InputStream stream = in) {
      ByteArrayOutputStream drained = new ByteArrayOutputStream();
      byte[] chunk = new byte[256];
      int read = stream.read(chunk);
      while (read != -1) {
        drained.write(chunk, 0, read);
        read = stream.read(chunk);
      }
      return drained.toByteArray();
    } catch (IOException unreadable) {
      throw new AssertionError("a returned binary stream could not be read", unreadable);
    }
  }

  private static Object drainChars(Reader in) {
    try (Reader reader = in) {
      StringBuilder drained = new StringBuilder();
      char[] chunk = new char[256];
      int read = reader.read(chunk);
      while (read != -1) {
        drained.append(chunk, 0, read);
        read = reader.read(chunk);
      }
      return drained.toString();
    } catch (IOException unreadable) {
      throw new AssertionError("a returned character stream could not be read", unreadable);
    }
  }

  private static String describeParity(@Nullable Object value) {
    return value instanceof byte[] ? Arrays.toString((byte[]) value) : String.valueOf(value);
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
