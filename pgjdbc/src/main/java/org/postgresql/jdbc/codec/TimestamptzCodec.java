/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingByteArrayOutputStream;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.TemporalCodecs;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Codec for PostgreSQL timestamptz (timestamp with time zone) type.
 */
public final class TimestamptzCodec implements StreamingBinaryCodec, TextCodec {

  public static final TimestamptzCodec INSTANCE = new TimestamptzCodec();

  private TimestamptzCodec() {
    // Singleton
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Timestamp.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (ctx.getJavaTimePreferences().prefersOffsetDateTime()) {
      return TemporalCodecs.decodeOffsetDateTimeBin(data, offset, length, ctx);
    }
    return TemporalCodecs.decodeTimestampBin(data, offset, length, true, ctx);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return TemporalCodecs.encodeTimestamptzBin(value, ctx);
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingByteArrayOutputStream out) throws SQLException, IOException {
    TemporalCodecs.writeTimestamptzBin(value, out, ctx);
  }

  @Override
  public @Nullable Object decodeText(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String text = data.toString();
    if (ctx.getJavaTimePreferences().prefersOffsetDateTime()) {
      return normalizeToUtc(TemporalCodecs.decodeOffsetDateTimeText(text, ctx));
    }
    return TemporalCodecs.decodeTimestampText(text, ctx);
  }

  /**
   * Normalizes an {@link OffsetDateTime} to UTC, so the text path agrees with
   * {@link TemporalCodecs#decodeOffsetDateTimeBin}, which always yields UTC.
   *
   * @param odt the value to normalize, or null
   * @return the same instant at {@link ZoneOffset#UTC}; null and the
   *     {@link OffsetDateTime#MIN}/{@link OffsetDateTime#MAX} sentinels come back unchanged
   */
  private static @Nullable OffsetDateTime normalizeToUtc(@Nullable OffsetDateTime odt) {
    if (odt == null || odt.equals(OffsetDateTime.MAX) || odt.equals(OffsetDateTime.MIN)) {
      return odt;
    }
    return odt.withOffsetSameInstant(ZoneOffset.UTC);
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (value instanceof Timestamp) {
      return TemporalCodecs.formatTimestamp((Timestamp) value, ctx);
    }
    if (value instanceof OffsetDateTime) {
      return TemporalCodecs.formatOffsetDateTime((OffsetDateTime) value, ctx);
    }
    if (value instanceof ZonedDateTime) {
      return TemporalCodecs.formatOffsetDateTime(((ZonedDateTime) value).toOffsetDateTime(), ctx);
    }
    if (value instanceof Instant) {
      return TemporalCodecs.formatOffsetDateTime(((Instant) value).atOffset(ZoneOffset.UTC), ctx);
    }
    if (value instanceof LocalDateTime) {
      return TemporalCodecs.formatLocalDateTime((LocalDateTime) value, ctx);
    }
    if (value instanceof java.util.Date) {
      @SuppressWarnings("JavaUtilDate")
      long time = ((java.util.Date) value).getTime();
      return TemporalCodecs.formatTimestamp(new Timestamp(time), ctx);
    }
    if (value instanceof String) {
      return TemporalCodecs.formatTimestamp(TemporalCodecs.decodeTimestampText((String) value, ctx), ctx);
    }
    throw Exceptions.cannotEncode(value, "timestamptz");
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    if (targetClass == Timestamp.class || targetClass == Object.class) {
      return targetClass.cast(TemporalCodecs.decodeTimestampBin(data, offset, length, true, ctx));
    }
    if (targetClass == OffsetDateTime.class) {
      return targetClass.cast(TemporalCodecs.decodeOffsetDateTimeBin(data, offset, length, ctx));
    }
    if (targetClass == ZonedDateTime.class) {
      // timestamptz is stored as UTC
      OffsetDateTime odt = TemporalCodecs.decodeOffsetDateTimeBin(data, offset, length, ctx);
      return targetClass.cast(odt.toZonedDateTime());
    }
    if (targetClass == Instant.class) {
      OffsetDateTime odt = TemporalCodecs.decodeOffsetDateTimeBin(data, offset, length, ctx);
      return targetClass.cast(odt.toInstant());
    }
    // LocalDate / LocalTime / LocalDateTime are intentionally rejected — they
    // drop the time zone information that this column carries; the JDBC
    // contract surfaces that as DATA_TYPE_MISMATCH.
    if (targetClass == Date.class) {
      // JDBC: getDate on a TIMESTAMPTZ column truncates the instant to midnight
      // in the target time zone.
      Timestamp t = TemporalCodecs.decodeTimestampBin(data, offset, length, true, ctx);
      return t == null ? null : targetClass.cast(TemporalCodecs.extractDate(t.getTime(), ctx));
    }
    if (targetClass == Time.class) {
      // JDBC: getTime on a binary TIMESTAMPTZ truncates the UTC instant to the day.
      Timestamp t = TemporalCodecs.decodeTimestampBin(data, offset, length, true, ctx);
      return t == null ? null : targetClass.cast(new Time(t.getTime() % TimeUnit.DAYS.toMillis(1)));
    }
    if (targetClass == java.util.Date.class) {
      return targetClass.cast(TemporalCodecs.decodeTimestampBin(data, offset, length, true, ctx));
    }
    if (targetClass == Long.class) {
      Timestamp t = TemporalCodecs.decodeTimestampBin(data, offset, length, true, ctx);
      return t == null ? null : targetClass.cast(t.getTime());
    }
    if (targetClass == String.class) {
      return targetClass.cast(TemporalCodecs.formatOffsetDateTimeBin(data, offset, length, ctx));
    }
    throw Exceptions.cannotDecode("timestamptz", targetClass.getName());
  }

  @Override
  public <T> @Nullable T decodeTextAs(CharSequence data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    String text = data.toString();
    if (targetClass == Timestamp.class || targetClass == Object.class) {
      return targetClass.cast(TemporalCodecs.decodeTimestampText(text, ctx));
    }
    if (targetClass == OffsetDateTime.class) {
      return targetClass.cast(normalizeToUtc(TemporalCodecs.decodeOffsetDateTimeText(text, ctx)));
    }
    if (targetClass == ZonedDateTime.class) {
      OffsetDateTime odt = normalizeToUtc(TemporalCodecs.decodeOffsetDateTimeText(text, ctx));
      return odt == null ? null : targetClass.cast(odt.toZonedDateTime());
    }
    if (targetClass == Instant.class) {
      OffsetDateTime odt = TemporalCodecs.decodeOffsetDateTimeText(text, ctx);
      return odt == null ? null : targetClass.cast(odt.toInstant());
    }
    // LocalDate / LocalTime / LocalDateTime are intentionally rejected — they
    // drop the time zone information that this column carries.
    if (targetClass == Date.class) {
      return targetClass.cast(TemporalCodecs.decodeDateText(text, ctx));
    }
    if (targetClass == Time.class) {
      // The same truncation the binary branch applies, over the same instant, so the two wire formats
      // agree. TimestampUtils.toTime cannot be used here: it anchors the time of day to 1970-01-01 in
      // the literal's own offset, which is a day later in UTC once the offset crosses midnight.
      Timestamp t = TemporalCodecs.decodeTimestampText(text, ctx);
      return t == null ? null : targetClass.cast(new Time(t.getTime() % TimeUnit.DAYS.toMillis(1)));
    }
    if (targetClass == java.util.Date.class) {
      return targetClass.cast(TemporalCodecs.decodeTimestampText(text, ctx));
    }
    if (targetClass == Long.class) {
      Timestamp t = TemporalCodecs.decodeTimestampText(text, ctx);
      return t == null ? null : targetClass.cast(t.getTime());
    }
    if (targetClass == String.class) {
      return targetClass.cast(text);
    }
    throw Exceptions.cannotDecode("timestamptz", targetClass.getName());
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return TemporalCodecs.formatOffsetDateTimeBin(data, offset, length, ctx);
  }

  @Override
  public @Nullable String decodeAsString(CharSequence data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return data.toString();
  }

}
