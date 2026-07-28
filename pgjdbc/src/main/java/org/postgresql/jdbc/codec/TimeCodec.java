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
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalTime;
import java.time.OffsetTime;

/**
 * Codec for PostgreSQL time (without time zone) type.
 */
public final class TimeCodec implements StreamingBinaryCodec, TextCodec {

  public static final TimeCodec INSTANCE = new TimeCodec();

  private TimeCodec() {
    // Singleton
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Time.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (ctx.getJavaTimePreferences().prefersLocalTime()) {
      return TemporalCodecs.decodeLocalTimeBin(data, offset, length, ctx);
    }
    return TemporalCodecs.decodeTimeBin(data, offset, length, ctx);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return TemporalCodecs.encodeTimeBin(value, ctx);
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingByteArrayOutputStream out) throws SQLException, IOException {
    TemporalCodecs.writeTimeBin(value, out, ctx);
  }

  @Override
  public @Nullable Object decodeText(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String text = data.toString();
    if (ctx.getJavaTimePreferences().prefersLocalTime()) {
      return TemporalCodecs.decodeLocalTimeText(text, ctx);
    }
    return TemporalCodecs.decodeTimeText(text, ctx);
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (value instanceof Time) {
      return TemporalCodecs.formatTime((Time) value, ctx);
    }
    if (value instanceof LocalTime) {
      return TemporalCodecs.formatLocalTime((LocalTime) value, ctx);
    }
    if (value instanceof OffsetTime) {
      // Caller asked for TIME (no time zone) — strip the offset.
      return TemporalCodecs.formatLocalTime(((OffsetTime) value).toLocalTime(), ctx);
    }
    if (value instanceof java.util.Date) {
      @SuppressWarnings("JavaUtilDate")
      long time = ((java.util.Date) value).getTime();
      return TemporalCodecs.formatTime(new Time(time), ctx);
    }
    if (value instanceof String) {
      return TemporalCodecs.formatTime(TemporalCodecs.decodeTimeText((String) value, ctx), ctx);
    }
    throw Exceptions.cannotEncode(value, "time");
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    if (targetClass == Time.class || targetClass == Object.class) {
      return targetClass.cast(TemporalCodecs.decodeTimeBin(data, offset, length, ctx));
    }
    if (targetClass == LocalTime.class) {
      return targetClass.cast(TemporalCodecs.decodeLocalTimeBin(data, offset, length, ctx));
    }
    if (targetClass == Timestamp.class) {
      // JDBC: getTimestamp on a TIME column anchors the time to 1970-01-01.
      Time t = TemporalCodecs.decodeTimeBin(data, offset, length, ctx);
      if (t == null) {
        return null;
      }
      Timestamp r = new Timestamp(t.getTime());
      r.setNanos(TemporalCodecs.decodeTimestampBin(data, offset, length, false, ctx).getNanos());
      return targetClass.cast(r);
    }
    if (targetClass == java.util.Date.class) {
      return targetClass.cast(TemporalCodecs.decodeTimeBin(data, offset, length, ctx));
    }
    if (targetClass == Long.class) {
      Time t = TemporalCodecs.decodeTimeBin(data, offset, length, ctx);
      return t == null ? null : targetClass.cast(t.getTime());
    }
    if (targetClass == String.class) {
      LocalTime lt = TemporalCodecs.decodeLocalTimeBin(data, offset, length, ctx);
      return lt == null ? null : targetClass.cast(TemporalCodecs.formatLocalTime(lt, ctx));
    }
    throw Exceptions.cannotDecode("time", targetClass.getName());
  }

  @Override
  public <T> @Nullable T decodeTextAs(CharSequence data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    String text = data.toString();
    if (targetClass == Time.class || targetClass == Object.class) {
      return targetClass.cast(TemporalCodecs.decodeTimeText(text, ctx));
    }
    if (targetClass == LocalTime.class) {
      return targetClass.cast(TemporalCodecs.decodeLocalTimeText(text, ctx));
    }
    if (targetClass == Timestamp.class) {
      Time t = TemporalCodecs.decodeTimeText(text, ctx);
      if (t == null) {
        return null;
      }
      Timestamp r = new Timestamp(t.getTime());
      r.setNanos(TemporalCodecs.decodeTimestampText(text, ctx).getNanos());
      return targetClass.cast(r);
    }
    if (targetClass == java.util.Date.class) {
      return targetClass.cast(TemporalCodecs.decodeTimeText(text, ctx));
    }
    if (targetClass == Long.class) {
      Time t = TemporalCodecs.decodeTimeText(text, ctx);
      return t == null ? null : targetClass.cast(t.getTime());
    }
    if (targetClass == String.class) {
      return targetClass.cast(text);
    }
    throw Exceptions.cannotDecode("time", targetClass.getName());
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    // Render from the wire rather than via decodeLocalTimeBin, so 24:00:00 -- which has no LocalTime
    // form and makes getObject refuse -- still reads back as the string "24:00:00".
    return TemporalCodecs.formatLocalTimeBin(data, offset, length, ctx);
  }

  @Override
  public @Nullable String decodeAsString(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String text = data.toString();
    // Preserve the original text (including microseconds) — java.sql.Time.toString()
    // would truncate the fractional part.
    return text;
  }

}
