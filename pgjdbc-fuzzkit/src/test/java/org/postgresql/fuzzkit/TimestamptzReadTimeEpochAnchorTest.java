/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.WireValueSlice;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.jdbc.PgSQLInputBinary;
import org.postgresql.jdbc.PgSQLInputText;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Time;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

/**
 * Fails when {@code getTime()} on a {@code timestamptz} returns a {@code java.sql.Time} whose date part
 * is not the epoch day.
 *
 * <p>{@code java.sql.Time} carries a whole instant and only presents as a time of day. Its javadoc
 * requires the date components to be the zero epoch of January 1, 1970, and {@code getYear} /
 * {@code getMonth} / {@code getDate} throw so nobody reads them. {@code getTime}, {@code equals} and
 * {@code compareTo} do not throw, so a wrong date leaks out through every comparison.
 *
 * <p>Truncating the instant with {@code millis % 86400000} looks like it anchors to the epoch and does
 * not: Java's remainder keeps the sign of its left operand, so any instant before 1970 lands on
 * 1969-12-31 instead. {@code TemporalCodecs.extractTime} anchors properly, and {@code TimestampCodec}
 * already used it.
 *
 * <p>Neither fuzz oracle reaches this. Cross-format parity cannot: both wire formats truncated the same
 * way, so they agreed with each other while both were wrong. And the {@code ReadCoercions} outcome is
 * value-blind, so a returned {@code Time} satisfies it whatever instant it carries.
 *
 * <p>What this test cannot cover is the zone the anchoring happens in. {@code extractTime} anchors in the
 * caller's {@code Calendar} zone, or the JVM default when there is none, whereas the remainder anchored
 * in UTC unconditionally — a difference that shows only on a JVM whose default zone is not UTC. The
 * offline context builder exposes no caller zone, and this module pins {@code user.timezone=UTC}, so the
 * two coincide here.
 */
class TimestamptzReadTimeEpochAnchorTest {

  private static final PgType COMPOSITE = FuzzComposites.singleField(Oid.TIMESTAMPTZ);
  private static final PgType TIMESTAMPTZ =
      CodecFuzzSupport.scalar(Oid.TIMESTAMPTZ, "timestamptz", 'D');

  /**
   * Instants either side of the epoch, and either side of midnight. The pre-epoch pair is what the
   * remainder got wrong; the post-epoch pair is the control that pins the time of day itself.
   */
  static List<Arguments> instants() {
    return Arrays.asList(
        Arguments.of("1969-03-04T23:30:00Z", "23:30:00"),
        Arguments.of("1969-03-04T00:30:00Z", "00:30:00"),
        Arguments.of("2020-01-02T23:30:00Z", "23:30:00"),
        Arguments.of("2020-01-02T00:30:00Z", "00:30:00"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("instants")
  void timeCarriesTheEpochDay(String instant, String expectedTimeOfDay) throws SQLException {
    PgCodecContext ctx = context();
    OffsetDateTime value = OffsetDateTime.parse(instant);

    for (Format format : Format.values()) {
      Time read = readTime(value, format, ctx);
      assertEquals("1970-01-01 " + expectedTimeOfDay, render(read),
          () -> "getTime on a timestamptz over " + format
              + " must return the time of day on the epoch day");
    }
  }

  private static Time readTime(OffsetDateTime value, Format format, PgCodecContext ctx)
      throws SQLException {
    WireValueSlice field = Codecs.encode(value, TIMESTAMPTZ, ctx, format);
    if (format == Format.TEXT) {
      return new PgSQLInputText(new String[]{field.asString(StandardCharsets.UTF_8)}, COMPOSITE, ctx)
          .readTime();
    }
    return new PgSQLInputBinary(singleFieldComposite(field.toByteArray()), COMPOSITE, ctx).readTime();
  }

  /**
   * Renders the whole instant the {@code Time} carries, date part included, in the zone the codec
   * anchors against -- the JVM default, since the offline context supplies no caller zone. This is the
   * zone {@code Time.toString()} formats in, so it is the reading the javadoc's rule is about.
   */
  private static String render(Time time) {
    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    format.setTimeZone(TimeZone.getDefault());
    return format.format(time);
  }

  private static byte[] singleFieldComposite(byte[] body) {
    byte[] wire = new byte[12 + body.length];
    ByteConverter.int4(wire, 0, 1);
    ByteConverter.int4(wire, 4, Oid.TIMESTAMPTZ);
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
