/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The per-type {@code getObject} java.time preferences, matching the per-type connection properties.
 * Each flag makes {@code decode(..., Object.class)} on that temporal type yield the java.time class
 * rather than the {@code java.sql} one. Instances are immutable, and
 * {@link CodecContextBuilder#javaTimePreferences(JavaTimePreferences)} takes one.
 *
 * <p>Build one with {@link #builder()} and set only the flags you need; unset flags default to
 * {@code false}. {@link #NONE} is the all-false value: every temporal type yields its
 * {@code java.sql} class.
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public final class JavaTimePreferences {

  /** No java.time preference for any temporal type: every type yields its {@code java.sql} class. */
  public static final JavaTimePreferences NONE = new JavaTimePreferences(false, false, false, false, false);

  private final boolean forDate;
  private final boolean forTime;
  private final boolean forTimetz;
  private final boolean forTimestamp;
  private final boolean forTimestamptz;

  private JavaTimePreferences(boolean forDate, boolean forTime, boolean forTimetz,
                              boolean forTimestamp, boolean forTimestamptz) {
    this.forDate = forDate;
    this.forTime = forTime;
    this.forTimetz = forTimetz;
    this.forTimestamp = forTimestamp;
    this.forTimestamptz = forTimestamptz;
  }

  /**
   * A fresh builder with every flag {@code false}.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Whether {@code date} prefers {@link java.time.LocalDate} over {@link java.sql.Date}. */
  public boolean prefersLocalDate() {
    return forDate;
  }

  /** Whether {@code time} prefers {@link java.time.LocalTime} over {@link java.sql.Time}. */
  public boolean prefersLocalTime() {
    return forTime;
  }

  /** Whether {@code timetz} prefers {@link java.time.OffsetTime} over {@link java.sql.Time}. */
  public boolean prefersOffsetTime() {
    return forTimetz;
  }

  /** Whether {@code timestamp} prefers {@link java.time.LocalDateTime} over {@link java.sql.Timestamp}. */
  public boolean prefersLocalDateTime() {
    return forTimestamp;
  }

  /**
   * Whether {@code timestamptz} prefers {@link java.time.OffsetDateTime} over {@link java.sql.Timestamp}.
   */
  public boolean prefersOffsetDateTime() {
    return forTimestamptz;
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof JavaTimePreferences)) {
      return false;
    }
    JavaTimePreferences other = (JavaTimePreferences) o;
    return forDate == other.forDate && forTime == other.forTime && forTimetz == other.forTimetz
        && forTimestamp == other.forTimestamp && forTimestamptz == other.forTimestamptz;
  }

  @Override
  public int hashCode() {
    int result = Boolean.hashCode(forDate);
    result = 31 * result + Boolean.hashCode(forTime);
    result = 31 * result + Boolean.hashCode(forTimetz);
    result = 31 * result + Boolean.hashCode(forTimestamp);
    result = 31 * result + Boolean.hashCode(forTimestamptz);
    return result;
  }

  @Override
  public String toString() {
    return "JavaTimePreferences{date=" + forDate + ", time=" + forTime + ", timetz=" + forTimetz
        + ", timestamp=" + forTimestamp + ", timestamptz=" + forTimestamptz + '}';
  }

  /** A fluent builder for {@link JavaTimePreferences}; each setter defaults to {@code false}. */
  public static final class Builder {

    private boolean forDate;
    private boolean forTime;
    private boolean forTimetz;
    private boolean forTimestamp;
    private boolean forTimestamptz;

    private Builder() {
    }

    /**
     * Sets whether {@code date} prefers {@link java.time.LocalDate} over {@link java.sql.Date}.
     *
     * @param prefer true to prefer java.time
     * @return this builder
     */
    public Builder prefersLocalDate(boolean prefer) {
      this.forDate = prefer;
      return this;
    }

    /**
     * Sets whether {@code time} prefers {@link java.time.LocalTime} over {@link java.sql.Time}.
     *
     * @param prefer true to prefer java.time
     * @return this builder
     */
    public Builder prefersLocalTime(boolean prefer) {
      this.forTime = prefer;
      return this;
    }

    /**
     * Sets whether {@code timetz} prefers {@link java.time.OffsetTime} over {@link java.sql.Time}.
     *
     * @param prefer true to prefer java.time
     * @return this builder
     */
    public Builder prefersOffsetTime(boolean prefer) {
      this.forTimetz = prefer;
      return this;
    }

    /**
     * Sets whether {@code timestamp} prefers {@link java.time.LocalDateTime} over
     * {@link java.sql.Timestamp}.
     *
     * @param prefer true to prefer java.time
     * @return this builder
     */
    public Builder prefersLocalDateTime(boolean prefer) {
      this.forTimestamp = prefer;
      return this;
    }

    /**
     * Sets whether {@code timestamptz} prefers {@link java.time.OffsetDateTime} over
     * {@link java.sql.Timestamp}.
     *
     * @param prefer true to prefer java.time
     * @return this builder
     */
    public Builder prefersOffsetDateTime(boolean prefer) {
      this.forTimestamptz = prefer;
      return this;
    }

    /**
     * Builds the immutable preferences.
     *
     * @return the preferences
     */
    public JavaTimePreferences build() {
      return new JavaTimePreferences(forDate, forTime, forTimetz, forTimestamp, forTimestamptz);
    }
  }
}
