/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.compat;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.UUID;

/**
 * The read-half axis: one {@link ResultSet} getter each. The differential oracle drives every accessor on
 * the same server value through both drivers and compares the outcomes. Boxing (for example {@code getInt}
 * to {@code Integer}) is intentional so that {@link OutcomeComparator#normalize} can record the returned
 * type alongside the value.
 */
public enum Accessor {
  GET_STRING {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getString(column);
    }
  },
  GET_BOOLEAN {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getBoolean(column);
    }
  },
  GET_INT {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getInt(column);
    }
  },
  GET_LONG {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getLong(column);
    }
  },
  GET_BIG_DECIMAL {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getBigDecimal(column);
    }
  },
  GET_BYTES {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getBytes(column);
    }
  },
  GET_DATE {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getDate(column);
    }
  },
  GET_TIME {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getTime(column);
    }
  },
  GET_TIMESTAMP {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getTimestamp(column);
    }
  },
  GET_BYTE {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getByte(column);
    }
  },
  GET_SHORT {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getShort(column);
    }
  },
  GET_FLOAT {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getFloat(column);
    }
  },
  GET_DOUBLE {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getDouble(column);
    }
  },
  GET_ARRAY {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getArray(column);
    }
  },
  GET_OBJECT {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getObject(column);
    }
  },
  GET_OBJECT_LOCAL_DATE {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getObject(column, LocalDate.class);
    }
  },
  GET_OBJECT_LOCAL_DATE_TIME {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getObject(column, LocalDateTime.class);
    }
  },
  GET_OBJECT_OFFSET_DATE_TIME {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getObject(column, OffsetDateTime.class);
    }
  },
  GET_OBJECT_UUID {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getObject(column, UUID.class);
    }
  },
  GET_OBJECT_LOCAL_TIME {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getObject(column, LocalTime.class);
    }
  },
  GET_OBJECT_OFFSET_TIME {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getObject(column, OffsetTime.class);
    }
  },
  GET_OBJECT_INSTANT {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getObject(column, Instant.class);
    }
  },
  GET_OBJECT_BIG_DECIMAL {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getObject(column, BigDecimal.class);
    }
  },
  GET_OBJECT_STRING {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getObject(column, String.class);
    }
  },
  GET_OBJECT_BYTES {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getObject(column, byte[].class);
    }
  },
  GET_DATE_CAL {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getDate(column, calendar());
    }
  },
  GET_TIME_CAL {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getTime(column, calendar());
    }
  },
  GET_TIMESTAMP_CAL {
    @Override
    @Nullable Object get(ResultSet rs, int column) throws SQLException {
      return rs.getTimestamp(column, calendar());
    }
  };

  /**
   * A calendar in a fixed non-UTC, non-JVM-default zone. The temporal-with-calendar getters must honour
   * it, so a fresh instance is handed out per call: the JDBC drivers may mutate a calendar passed to a
   * getter, and the current and baseline drivers must not share one.
   */
  private static GregorianCalendar calendar() {
    return new GregorianCalendar(TimeZone.getTimeZone("GMT+05:00"));
  }

  abstract @Nullable Object get(ResultSet rs, int column) throws SQLException;
}
