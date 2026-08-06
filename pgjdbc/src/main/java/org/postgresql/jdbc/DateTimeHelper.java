/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.Provider;
import org.postgresql.core.QueryExecutor;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Creates a {@link TimestampUtils} lazily and caches the default time zone for the result set or
 * statement that owns it.
 *
 * <p>{@code PgResultSet} and {@code PgStatement} each hold their own, because
 * {@link TimestampUtils} is not thread-safe.</p>
 */
final class DateTimeHelper {

  private final QueryExecutor queryExecutor;
  private @Nullable TimestampUtils timestampUtils;
  private @Nullable TimeZone defaultTimeZone;

  DateTimeHelper(QueryExecutor queryExecutor) {
    this.queryExecutor = queryExecutor;
  }

  /**
   * Returns the {@link TimestampUtils} instance, creating it on first use.
   */
  TimestampUtils getTimestampUtils() {
    if (timestampUtils == null) {
      timestampUtils = new TimestampUtils(
          !queryExecutor.getIntegerDateTimes(),
          (Provider<TimeZone>) new QueryExecutorTimeZoneProvider(queryExecutor)
      );
    }
    return timestampUtils;
  }

  /**
   * Returns a Calendar using the default timezone, with caching to avoid
   * repeated TimeZone lookups.
   */
  Calendar getDefaultCalendar() {
    if (getTimestampUtils().hasFastDefaultTimeZone()) {
      return getTimestampUtils().getSharedCalendar(null);
    }
    Calendar sharedCalendar = getTimestampUtils().getSharedCalendar(defaultTimeZone);
    if (defaultTimeZone == null) {
      defaultTimeZone = sharedCalendar.getTimeZone();
    }
    return sharedCalendar;
  }

  /**
   * Resets the cached default timezone. Called after statement execution
   * in case the server timezone changed during execution.
   */
  void resetDefaultTimeZone() {
    defaultTimeZone = null;
  }
}
