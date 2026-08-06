/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.WireValueSlice;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.jdbc.PgSQLInputBinary;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.Timestamp;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.TimeZone;

/**
 * Fails when the {@code timetz} codec reads a value from the start of the buffer instead of from the
 * value's own offset.
 *
 * <p>A binary codec is handed the whole buffer plus the offset and length of the value inside it, and
 * every read it performs must start at that offset. {@link PgSQLInputBinary} is where a non-zero offset
 * actually occurs: it decodes a composite field in place, so the buffer holds the entire row and the
 * field body sits past the composite header. A codec that reads from index 0 there lands on the header
 * -- the field count and the field OID -- and returns a value assembled from framing bytes, with no
 * exception to mark it.
 *
 * <p>{@code getTimestamp} on a {@code timetz} field is the read that had this defect. It recovers the
 * sub-second part with a second decode of the value's first eight bytes and passed a literal 0 as the
 * offset for it, so the seconds came from the field and the fraction came from the header.
 *
 * <p>The two tests come at it from the two directions the defect can be caught from: the reader the
 * driver actually uses, and the shared offset-invariance oracle
 * ({@link CodecFuzzSupport#decodeScalarBinaryOffsetInvariant}) the {@code *_binary} fuzz targets run.
 * The oracle leg is a pin on the oracle as much as on the codec -- it has no {@code timetz} binary seed
 * corpus, so a guided campaign has to synthesise a well-formed twelve-byte value before it reaches this,
 * and here that value is handed to it directly.
 */
class TimetzFieldOffsetReadTest {

  /** The composite whose single {@code timetz} attribute the reader decodes in place. */
  private static final PgType COMPOSITE = FuzzComposites.singleField(Oid.TIMETZ);

  /** The bare {@code timetz} type, for encoding and decoding the field body outside any composite. */
  private static final PgType TIMETZ = CodecFuzzSupport.scalar(Oid.TIMETZ, "timetz", 'D');

  /**
   * A {@code timetz} with a non-zero microsecond part, which is the part the defect corrupted. The zone
   * offset is non-zero too, so a decode that ignores it cannot land on the right answer by accident.
   */
  private static final OffsetTime VALUE =
      OffsetTime.of(LocalTime.of(10, 15, 30, 123_456_000), ZoneOffset.ofHours(5));

  /** Nanoseconds {@link #VALUE} carries; {@code timetz} resolves to microseconds, so this survives. */
  private static final int EXPECTED_NANOS = 123_456_000;

  /**
   * Reads the composite's {@code timetz} field as a {@code Timestamp} through the binary
   * {@code SQLInput} adapter, where the field body starts twelve bytes into the row buffer.
   */
  @Test
  void readTimestampTakesTheSubSecondPartFromTheField() throws SQLException {
    PgCodecContext ctx = context();
    byte[] body = fieldBody(ctx);
    SQLInput in = new PgSQLInputBinary(singleFieldComposite(body), COMPOSITE, ctx);

    Timestamp read = in.readTimestamp();

    assertNotNull(read, "readTimestamp refused a well-formed timetz field");
    assertEquals(EXPECTED_NANOS, read.getNanos(),
        "readTimestamp must take the sub-second part from the field, not from the composite header");
    assertEquals(Codecs.decode(WireValueSlice.of(Format.BINARY, body, 0, body.length), TIMETZ, ctx,
        Timestamp.class), read,
        "a timetz field read out of a composite must equal the same bytes decoded on their own");
  }

  /**
   * Runs a well-formed {@code timetz} value through the offset-invariance oracle, which decodes it at
   * offset 0 and again behind a canary prefix and compares every target class the type declares.
   */
  @Test
  void wellFormedValueSurvivesTheOffsetInvariantOracle() throws SQLException {
    CodecFuzzSupport.decodeScalarBinaryOffsetInvariant(fieldBody(context()), Oid.TIMETZ);
  }

  /** The canonical binary body of {@link #VALUE}, as the server would send it for a {@code timetz}. */
  private static byte[] fieldBody(PgCodecContext ctx) throws SQLException {
    return Codecs.encode(VALUE, TIMETZ, ctx, Format.BINARY).toByteArray();
  }

  /**
   * Wraps one field body in the binary composite wire the reader expects: {@code int4} field count,
   * then the field's {@code int4} OID, {@code int4} length, and body.
   */
  private static byte[] singleFieldComposite(byte[] body) {
    byte[] wire = new byte[12 + body.length];
    ByteConverter.int4(wire, 0, 1);
    ByteConverter.int4(wire, 4, Oid.TIMETZ);
    ByteConverter.int4(wire, 8, body.length);
    System.arraycopy(body, 0, wire, 12, body.length);
    return wire;
  }

  private static PgCodecContext context() throws SQLException {
    return (PgCodecContext) OfflineCodecContexts.offlineBuilder()
        .type(COMPOSITE)
        .clientTimeZone(TimeZone.getDefault())
        .build();
  }
}
