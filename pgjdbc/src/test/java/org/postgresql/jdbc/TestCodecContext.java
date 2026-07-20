/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrefersJavaTime;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.TimeZone;

/**
 * Factory for creating CodecContext instances in unit tests without a database connection.
 */
public final class TestCodecContext {

  private TestCodecContext() {
  }

  /**
   * Creates a CodecContext for testing with default settings (UTC, UTF-8, no java.time preference).
   */
  public static CodecContext create() {
    return create(PrefersJavaTime.NONE);
  }

  /**
   * Creates a CodecContext for testing with specified java.time preferences.
   */
  public static CodecContext create(PrefersJavaTime prefersJavaTime) {
    return create(StandardCharsets.UTF_8, prefersJavaTime);
  }

  /**
   * Creates a CodecContext for testing with specified charset and java.time preferences.
   */
  public static CodecContext create(Charset charset, PrefersJavaTime prefersJavaTime) {
    TimestampUtils timestampUtils = new TimestampUtils(false, () -> TimeZone.getTimeZone("UTC"));
    return new PgCodecContext(timestampUtils, charset, prefersJavaTime);
  }

  /**
   * Creates a CodecContext that prefers {@link java.time.LocalDate} for {@code date}.
   */
  public static CodecContext preferringJavaTimeForDate() {
    return create(PrefersJavaTime.builder().date(true).build());
  }

  /**
   * Creates a CodecContext that prefers {@link java.time.LocalTime} for {@code time}.
   */
  public static CodecContext preferringJavaTimeForTime() {
    return create(PrefersJavaTime.builder().time(true).build());
  }

  /**
   * Creates a CodecContext that prefers {@link java.time.OffsetTime} for {@code timetz}.
   */
  public static CodecContext preferringJavaTimeForTimetz() {
    return create(PrefersJavaTime.builder().timetz(true).build());
  }

  /**
   * Creates a CodecContext that prefers {@link java.time.LocalDateTime} for {@code timestamp}.
   */
  public static CodecContext preferringJavaTimeForTimestamp() {
    return create(PrefersJavaTime.builder().timestamp(true).build());
  }

  /**
   * Creates a CodecContext that prefers {@link java.time.OffsetDateTime} for {@code timestamptz}.
   */
  public static CodecContext preferringJavaTimeForTimestamptz() {
    return create(PrefersJavaTime.builder().timestamptz(true).build());
  }

  /**
   * Creates a CodecContext for testing with the {@code convertBooleanToNumeric}
   * flag set to the requested value (other flags default to false, UTF-8, UTC).
   */
  public static CodecContext withConvertBooleanToNumeric(boolean convertBooleanToNumeric) {
    TimestampUtils timestampUtils = new TimestampUtils(false, () -> TimeZone.getTimeZone("UTC"));
    return new PgCodecContext(timestampUtils, StandardCharsets.UTF_8,
        PrefersJavaTime.NONE, convertBooleanToNumeric);
  }

  /**
   * Returns a {@link TimestampUtils} configured identically to the test contexts (integer
   * datetimes, UTC). Codec tests use it to build expected values without reaching the codec
   * context's internal temporal engine.
   */
  public static TimestampUtils timestampUtils() {
    return new TimestampUtils(false, () -> TimeZone.getTimeZone("UTC"));
  }
}
