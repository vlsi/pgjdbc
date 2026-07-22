/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TypeName;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.OfflineCodecs;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PGRange;
import org.postgresql.util.PGmultirange;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayOutputStream;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * Offline decode oracle for {@link MultirangeCodec}'s binary path, driven by hand-assembled
 * {@code multirange_send} wire images. This pins the multirange framing — the {@code int32} range
 * count and the per-range {@code int32} length header — and the delegation of each range's flags to
 * {@link RangeCodec}, independently of an encode/decode round-trip.
 *
 * <p>The multirange resolves through an offline registry to {@code int4range} and its {@code int4}
 * subtype, so each inner range is a plain {@code range_send} payload over big-endian int4 bounds.
 *
 * <p>Binary layout (see {@link MultirangeCodec}): an {@code int32} range count, then for each range
 * an {@code int32} byte length followed by that range's {@code range_send} payload.
 */
class MultirangeCodecBinaryDecodeTest {

  private static final byte FLAG_EMPTY = 0x01;
  private static final byte FLAG_LOWER_INCLUSIVE = 0x02;

  private static final PgType INT4 = new PgType(
      TypeName.of("pg_catalog", "int4"), "int4", Oid.INT4, 'b', 'N', -1, 0, 0, 0);

  private static final PgType INT4RANGE = new PgType(
      TypeName.of("pg_catalog", "int4range"), "int4range", 3904,
      'r', 'R', -1, 0, 0, 0).withRangeSubtype(Oid.INT4);

  private static final PgType INT4MULTIRANGE = new PgType(
      TypeName.of("pg_catalog", "int4multirange"), "int4multirange", 4451,
      'm', 'R', -1, 0, 0, 0).withMultirangeRange(3904);

  private static final CodecContext CTX = OfflineCodecs.builder()
      .type(INT4)
      .type(INT4RANGE)
      .type(INT4MULTIRANGE)
      .build();

  private static PGmultirange<?> decode(byte[] wire) throws SQLException {
    return (PGmultirange<?>) MultirangeCodec.INSTANCE
        .decodeBinary(wire, 0, wire.length, INT4MULTIRANGE, CTX);
  }

  // -------- framing: count, per-range length, and flag delegation --------

  @Test
  void emptyMultirange() throws SQLException {
    // A zero range count and nothing else.
    PGmultirange<?> mr = decode(multirange());
    assertTrue(mr.getRanges().isEmpty());
    assertEquals("int4multirange", mr.getType());
  }

  @Test
  void singleRangeDelegatesFlags() throws SQLException {
    PGmultirange<?> mr = decode(multirange(range(FLAG_LOWER_INCLUSIVE, 1, 5)));
    List<? extends PGRange<?>> ranges = mr.getRanges();
    assertEquals(1, ranges.size());
    assertEquals(1, ranges.get(0).getLower());
    assertEquals(5, ranges.get(0).getUpper());
    assertTrue(ranges.get(0).isLowerInclusive());
  }

  @Test
  void severalRanges() throws SQLException {
    PGmultirange<?> mr = decode(multirange(
        range(FLAG_LOWER_INCLUSIVE, 1, 5), range(FLAG_LOWER_INCLUSIVE, 10, 20)));
    assertEquals(2, mr.getRanges().size());
    assertEquals(1, mr.getRanges().get(0).getLower());
    assertEquals(10, mr.getRanges().get(1).getLower());
  }

  @Test
  void emptyInnerRangeIsDropped() throws SQLException {
    // The server normalises empty ranges out of a multirange; the decoder mirrors that, so a range
    // whose payload is a bare empty flag decodes to a zero-length multirange.
    assertTrue(decode(multirange(new byte[]{FLAG_EMPTY})).getRanges().isEmpty());
  }

  @Test
  void emptyInnerRangeIsDroppedAmongOthers() throws SQLException {
    PGmultirange<?> mr = decode(multirange(
        range(FLAG_LOWER_INCLUSIVE, 1, 2), new byte[]{FLAG_EMPTY}, range(FLAG_LOWER_INCLUSIVE, 5, 9)));
    assertEquals(2, mr.getRanges().size());
    assertEquals(1, mr.getRanges().get(0).getLower());
    assertEquals(5, mr.getRanges().get(1).getLower());
  }

  @Test
  void boundsAreTypedFromSubtype() throws SQLException {
    PGmultirange<?> mr = decode(multirange(range(FLAG_LOWER_INCLUSIVE, 1, 5)));
    assertInstanceOf(Integer.class, mr.getRanges().get(0).getLower());
  }

  @Test
  void decodesOffThePassedSliceWithoutCopying() throws SQLException {
    byte[] wire = multirange(range(FLAG_LOWER_INCLUSIVE, 1, 5), range(FLAG_LOWER_INCLUSIVE, 10, 20));
    byte[] embedded = new byte[5 + wire.length + 3];
    Arrays.fill(embedded, (byte) 0xEE);
    System.arraycopy(wire, 0, embedded, 5, wire.length);
    PGmultirange<?> mr = (PGmultirange<?>) MultirangeCodec.INSTANCE
        .decodeBinary(embedded, 5, wire.length, INT4MULTIRANGE, CTX);
    assertEquals(2, mr.getRanges().size());
    assertEquals(10, mr.getRanges().get(1).getLower());
  }

  // -------- malformed wire: each guard surfaces its own DATA_ERROR --------

  @Test
  void missingRangeCount() {
    // Fewer than the four bytes an int32 range count needs.
    assertDataError(() -> decode(new byte[]{0, 0, 0}));
  }

  @Test
  void negativeRangeCount() {
    assertDataError(() -> decode(int4(-1)));
  }

  @Test
  void missingRangeLength() {
    // The count promises one range, but no length header follows.
    assertDataError(() -> decode(int4(1)));
  }

  @Test
  void rangeTruncated() {
    // A range length that overruns the buffer.
    byte[] wire = concat(int4(1), int4(100), new byte[]{FLAG_LOWER_INCLUSIVE});
    assertDataError(() -> decode(wire));
  }

  @Test
  void negativeRangeLength() {
    byte[] wire = concat(int4(1), int4(-1));
    assertDataError(() -> decode(wire));
  }

  @Test
  void malformedInnerRangePropagates() {
    // A well-framed range slot whose payload is a broken range_send image: the flags claim a finite
    // lower bound, but the one-byte payload has no length header. The range-level guard must surface.
    byte[] wire = concat(int4(1), int4(1), new byte[]{0});
    assertDataError(() -> decode(wire));
  }

  @Test
  void trailingBytesAfterLastRangeAreRejected() {
    // multirange_recv reads exactly count ranges, then pq_getmsgend rejects any leftover.
    byte[] wire = concat(multirange(range(FLAG_LOWER_INCLUSIVE, 1, 5)), new byte[]{0x7F});
    assertDataError(() -> decode(wire));
  }

  @Test
  void lengthZeroIsRejected() {
    // A multirange value is at least a four-byte count; multirange_recv errors reading that count off
    // an empty message. A SQL NULL never reaches a codec, so a zero-length slice is malformed, not NULL.
    assertDataError(() -> MultirangeCodec.INSTANCE.decodeBinary(new byte[0], 0, 0, INT4MULTIRANGE, CTX));
    byte[] padding = {0x01, 0x02, 0x03};
    assertDataError(() -> MultirangeCodec.INSTANCE.decodeBinary(padding, 2, 0, INT4MULTIRANGE, CTX));
  }

  // -------- wire builders --------

  /** Frames a multirange: an int4 range count, then each range as an int4 length plus its payload. */
  private static byte[] multirange(byte[]... rangePayloads) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    write(out, int4(rangePayloads.length));
    for (byte[] payload : rangePayloads) {
      write(out, int4(payload.length));
      write(out, payload);
    }
    return out.toByteArray();
  }

  /** A finite range_send payload: a flags byte, then the lower and upper int4 bounds. */
  private static byte[] range(byte flags, int lower, int upper) {
    return concat(new byte[]{flags}, bound(lower), bound(upper));
  }

  /** A finite int4 bound on the wire: a 4-byte length header of 4, then the int4 value. */
  private static byte[] bound(int value) {
    return concat(int4(4), int4(value));
  }

  /** Big-endian int4; the count, the length headers, and the int4 payloads all use this encoding. */
  private static byte[] int4(int value) {
    byte[] b = new byte[4];
    ByteConverter.int4(b, 0, value);
    return b;
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      write(out, part);
    }
    return out.toByteArray();
  }

  private static void write(ByteArrayOutputStream out, byte[] bytes) {
    out.write(bytes, 0, bytes.length);
  }

  private static void assertDataError(Executable decode) {
    PSQLException ex = assertThrows(PSQLException.class, decode);
    assertEquals(PSQLState.DATA_ERROR.getState(), ex.getSQLState(), ex.getMessage());
  }
}
