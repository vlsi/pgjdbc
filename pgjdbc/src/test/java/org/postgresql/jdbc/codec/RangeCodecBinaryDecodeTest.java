/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TypeName;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.OfflineCodecs;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PGRange;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayOutputStream;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * Offline decode oracle for {@link RangeCodec}'s binary path, driven by hand-assembled
 * {@code range_send} wire images rather than an encode/decode round-trip. Building the flags byte and
 * the bound length headers by hand pins the decoder's flag handling on its own: a round-trip cannot,
 * because a symmetric bug in {@code encodeBinary} and {@code decodeBinary} would agree with itself.
 *
 * <p>The subtype resolves to {@code int4} through an offline registry, so each present bound decodes
 * to an {@link Integer} and its four wire bytes are a plain big-endian int4.
 *
 * <p>Binary layout (see {@link RangeCodec}): one flags byte — empty {@code 0x01}, lower inclusive
 * {@code 0x02}, upper inclusive {@code 0x04}, lower infinite {@code 0x08}, upper infinite
 * {@code 0x10} — then, for each finite bound, a 4-byte length followed by that many bound bytes.
 */
class RangeCodecBinaryDecodeTest {

  // The wire flag bits, mirrored from RangeCodec so the tests assemble images the same way the server
  // does. RangeCodec keeps them private; restating them here documents the wire contract under test.
  private static final byte FLAG_EMPTY = 0x01;
  private static final byte FLAG_LOWER_INCLUSIVE = 0x02;
  private static final byte FLAG_UPPER_INCLUSIVE = 0x04;
  private static final byte FLAG_LOWER_INFINITE = 0x08;
  private static final byte FLAG_UPPER_INFINITE = 0x10;

  private static final PgType INT4 = new PgType(
      TypeName.of("pg_catalog", "int4"), "int4", Oid.INT4, 'b', 'N', -1, 0, 0, 0);

  private static final PgType INT4RANGE = new PgType(
      TypeName.of("pg_catalog", "int4range"), "int4range", 3904,
      'r', 'R', -1, 0, 0, 0).withRangeSubtype(Oid.INT4);

  private static final CodecContext CTX = OfflineCodecs.builder()
      .type(INT4)
      .type(INT4RANGE)
      .build();

  private static PGRange<?> decode(byte... wire) throws SQLException {
    return (PGRange<?>) RangeCodec.INSTANCE.decodeBinary(wire, 0, wire.length, INT4RANGE, CTX);
  }

  // -------- flag decoding: empty, inclusivity, infinity --------

  @Test
  void emptyFlag() throws SQLException {
    // The empty flag stands alone: no bound headers follow, and the subtype is never consulted.
    PGRange<?> r = decode(FLAG_EMPTY);
    assertTrue(r.isEmpty());
    assertEquals("int4range", r.getType());
  }

  @Test
  void exclusiveBoth() throws SQLException {
    PGRange<?> r = decode(range((byte) 0, 1, 10));
    assertEquals(1, r.getLower());
    assertEquals(10, r.getUpper());
    assertFalse(r.isLowerInclusive());
    assertFalse(r.isUpperInclusive());
    assertTrue(r.hasLowerBound());
    assertTrue(r.hasUpperBound());
    assertFalse(r.isEmpty());
  }

  @Test
  void inclusiveLowerExclusiveUpper() throws SQLException {
    PGRange<?> r = decode(range(FLAG_LOWER_INCLUSIVE, 1, 10));
    assertTrue(r.isLowerInclusive());
    assertFalse(r.isUpperInclusive());
  }

  @Test
  void exclusiveLowerInclusiveUpper() throws SQLException {
    PGRange<?> r = decode(range(FLAG_UPPER_INCLUSIVE, 1, 10));
    assertFalse(r.isLowerInclusive());
    assertTrue(r.isUpperInclusive());
  }

  @Test
  void inclusiveBoth() throws SQLException {
    PGRange<?> r = decode(range((byte) (FLAG_LOWER_INCLUSIVE | FLAG_UPPER_INCLUSIVE), 1, 10));
    assertTrue(r.isLowerInclusive());
    assertTrue(r.isUpperInclusive());
  }

  @Test
  void lowerInfinite() throws SQLException {
    // Lower infinite: no lower header on the wire, only the upper bound follows the flags byte.
    PGRange<?> r = decode(concat(new byte[]{FLAG_LOWER_INFINITE}, bound(10)));
    assertFalse(r.hasLowerBound());
    assertNull(r.getLower());
    assertTrue(r.hasUpperBound());
    assertEquals(10, r.getUpper());
  }

  @Test
  void upperInfinite() throws SQLException {
    PGRange<?> r = decode(concat(new byte[]{FLAG_UPPER_INFINITE}, bound(1)));
    assertTrue(r.hasLowerBound());
    assertEquals(1, r.getLower());
    assertFalse(r.hasUpperBound());
    assertNull(r.getUpper());
  }

  @Test
  void bothInfinite() throws SQLException {
    // Both bounds infinite: the whole value is a single flags byte, with no bound data at all.
    PGRange<?> r = decode((byte) (FLAG_LOWER_INFINITE | FLAG_UPPER_INFINITE));
    assertFalse(r.hasLowerBound());
    assertFalse(r.hasUpperBound());
    assertFalse(r.isEmpty());
  }

  @Test
  void boundsAreTypedFromSubtype() throws SQLException {
    // The subtype resolves to int4, so a bound decodes to an Integer, not its raw wire bytes.
    PGRange<?> r = decode(range((byte) 0, 1, 10));
    assertInstanceOf(Integer.class, r.getLower());
    assertInstanceOf(Integer.class, r.getUpper());
  }

  @Test
  void decodesOffThePassedSliceWithoutCopying() throws SQLException {
    // decodeBinary must read straight off (buf, start, len); embed the wire in noise at a non-zero
    // offset so a stray full-buffer read would pick up the padding.
    byte[] wire = range(FLAG_LOWER_INCLUSIVE, 7, 42);
    byte[] embedded = new byte[3 + wire.length + 2];
    Arrays.fill(embedded, (byte) 0xEE);
    System.arraycopy(wire, 0, embedded, 3, wire.length);
    PGRange<?> r =
        (PGRange<?>) RangeCodec.INSTANCE.decodeBinary(embedded, 3, wire.length, INT4RANGE, CTX);
    assertEquals(7, r.getLower());
    assertEquals(42, r.getUpper());
    assertTrue(r.isLowerInclusive());
  }

  // -------- malformed wire: each guard surfaces its own DATA_ERROR --------

  @Test
  void missingLowerBoundLength() {
    // Flags say the lower bound is finite, but the value ends right after the flags byte.
    assertDataError(() -> decode((byte) 0));
  }

  @Test
  void lowerBoundTruncated() {
    // A 4-byte lower length header, then fewer than four bound bytes.
    byte[] wire = concat(new byte[]{0}, int4(4), new byte[]{0x11, 0x22});
    assertDataError(() -> decode(wire));
  }

  @Test
  void missingUpperBoundLength() {
    // Lower bound is complete, but the upper length header is missing.
    byte[] wire = concat(new byte[]{0}, bound(1));
    assertDataError(() -> decode(wire));
  }

  @Test
  void upperBoundTruncated() {
    byte[] wire = concat(new byte[]{0}, bound(1), int4(4), new byte[]{0x33});
    assertDataError(() -> decode(wire));
  }

  @Test
  void lowerBoundNegativeLength() {
    // A finite bound length is never negative; range_recv's pq_getmsgbytes rejects it like an overrun.
    byte[] wire = concat(new byte[]{0}, int4(-1));
    assertDataError(() -> decode(wire));
  }

  @Test
  void upperBoundNegativeLength() {
    byte[] wire = concat(new byte[]{0}, bound(1), int4(-1));
    assertDataError(() -> decode(wire));
  }

  // -------- server parity: the receiving side rejects exactly what range_recv rejects --------

  @Test
  void lengthZeroIsRejected() {
    // A range value is at least one flags byte; range_recv errors reading it off an empty message.
    // A SQL NULL never reaches a codec (it is signalled by a -1 field length), so a zero-length slice
    // is malformed rather than NULL, at offset 0 and mid-buffer alike.
    assertDataError(() -> RangeCodec.INSTANCE.decodeBinary(new byte[0], 0, 0, INT4RANGE, CTX));
    byte[] padding = {0x01, 0x02, 0x03};
    assertDataError(() -> RangeCodec.INSTANCE.decodeBinary(padding, 2, 0, INT4RANGE, CTX));
  }

  @Test
  void trailingBytesAfterUpperAreRejected() {
    // range_recv consumes the flags byte and both bounds, then pq_getmsgend rejects any leftover.
    byte[] wire = concat(range((byte) 0, 1, 10), new byte[]{0x7F});
    assertDataError(() -> decode(wire));
  }

  @Test
  void trailingBytesAfterEmptyFlagAreRejected() {
    // An empty range is its lone flags byte; anything after it is leftover pq_getmsgend would reject.
    assertDataError(() -> decode(FLAG_EMPTY, (byte) 0x00));
  }

  // -------- wire builders --------

  /** Assembles a finite range: a flags byte, then the lower and upper int4 bounds. */
  private static byte[] range(byte flags, int lower, int upper) {
    return concat(new byte[]{flags}, bound(lower), bound(upper));
  }

  /** A finite int4 bound on the wire: a 4-byte length header of 4, then the int4 value. */
  private static byte[] bound(int value) {
    return concat(int4(4), int4(value));
  }

  /** Big-endian int4; both the length headers and the int4 payloads use this encoding. */
  private static byte[] int4(int value) {
    byte[] b = new byte[4];
    ByteConverter.int4(b, 0, value);
    return b;
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.write(part, 0, part.length);
    }
    return out.toByteArray();
  }

  private static void assertDataError(Executable decode) {
    PSQLException ex = assertThrows(PSQLException.class, decode);
    assertEquals(PSQLState.DATA_ERROR.getState(), ex.getSQLState(), ex.getMessage());
  }
}
