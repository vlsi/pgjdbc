/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TypeName;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.OfflineCodecs;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.PGRange;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.TimeZone;

/**
 * Pins the {@code tstzrange} bound rendering: a timestamptz bound keeps its session-zone offset in the
 * decoded range's text form, the way the backend's {@code range_out} renders it. Reading a range used to
 * decode each bound into a {@link Timestamp} and let {@link PGRange#toString()} re-render it, which drops
 * the offset; the codec now hands {@link PGRange} the bound's wire-faithful text instead.
 *
 * <p>Connectionless: the range subtype resolves to timestamptz through an offline registry, and the
 * client time zone is pinned so the binary bound formats to a fixed offset.
 */
class RangeCodecTstzOffsetTest {

  private static final PgType TIMESTAMPTZ = new PgType(
      TypeName.of("pg_catalog", "timestamptz"), "timestamptz", Oid.TIMESTAMPTZ,
      'b', 'D', -1, 0, Oid.TIMESTAMPTZ_ARRAY, 0);

  private static final PgType TSTZRANGE = new PgType(
      TypeName.of("pg_catalog", "tstzrange"), "tstzrange", 3910,
      'r', 'R', -1, 0, 3911, 0).withRangeSubtype(Oid.TIMESTAMPTZ);

  // A +03 session zone, so a binary bound renders to a fixed, offset-bearing text independent of the
  // JVM default zone the test happens to run under.
  private static final CodecContext CTX = OfflineCodecs.builder()
      .clientTimeZone(TimeZone.getTimeZone("GMT+03:00"))
      .type(TIMESTAMPTZ)
      .type(TSTZRANGE)
      .build();

  // Both bounds already in the +03 session zone, as the server renders a tstzrange over the wire.
  private static final String LITERAL =
      "[\"2020-01-01 00:00:00+03\",\"2020-12-31 20:00:00+03\"]";

  @Test
  void textDecodeKeepsBoundOffsets() throws SQLException {
    PGRange<?> range = (PGRange<?>) RangeCodec.INSTANCE.decodeText(LITERAL, TSTZRANGE, CTX);
    assertEquals(LITERAL, range.toString());
  }

  @Test
  void binaryDecodeRendersBoundsInSessionZone() throws SQLException {
    PGRange<?> textRange = (PGRange<?>) RangeCodec.INSTANCE.decodeText(LITERAL, TSTZRANGE, CTX);
    byte[] wire = RangeCodec.INSTANCE.encodeBinary(textRange, TSTZRANGE, CTX);
    PGRange<?> binaryRange =
        (PGRange<?>) RangeCodec.INSTANCE.decodeBinary(wire, 0, wire.length, TSTZRANGE, CTX);
    // range_send stores each bound as a bare instant; the codec must render it back in the session
    // zone with its offset, not as a plain timestamp.
    assertEquals(LITERAL, binaryRange.toString());
  }

  @Test
  void getStringMatchesGetObjectAcrossFormats() throws SQLException {
    byte[] wire = RangeCodec.INSTANCE.encodeBinary(
        RangeCodec.INSTANCE.decodeText(LITERAL, TSTZRANGE, CTX), TSTZRANGE, CTX);
    // Binary getString (decodeAsString) and getObject (decodeBinary + toString) render the same text.
    assertEquals(LITERAL, RangeCodec.INSTANCE.decodeAsString(wire, 0, wire.length, TSTZRANGE, CTX));
  }

  @Test
  void mutatingABoundDropsItsPinnedText() {
    Timestamp lower = Timestamp.valueOf("2020-01-01 00:00:00");
    Timestamp upper = Timestamp.valueOf("2020-12-31 20:00:00");
    PGRange<Timestamp> range = new PGRange<>(lower, upper, true, true);
    range.setBoundTexts("2020-01-01 00:00:00+03", "2020-12-31 20:00:00+03");
    assertEquals(LITERAL, range.toString());

    // Replacing the upper bound clears only its pinned text; the lower bound still renders from it.
    range.setUpper(Timestamp.valueOf("2021-06-01 12:00:00"));
    assertEquals("[\"2020-01-01 00:00:00+03\",\"2021-06-01 12:00:00\"]", range.toString());
  }
}
