/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.BiPredicate;

/**
 * After {@code updateRow()} the driver re-encodes the new value into the in-memory row buffer so a
 * later getter sees the update without a server round-trip. That buffer encode goes through the
 * codec registry ({@code encodeRowBufferColumnViaCodec}). This test pins the invariant that the
 * codec-encoded row buffer agrees with what the database actually stored, across the scalar types
 * whose text encoding used to be hand-rolled in {@code setRowBufferColumn}, in both the text and
 * binary wire formats.
 *
 * <p>The oracle reads the freshly written row buffer and a fresh {@code SELECT} of the same row with
 * the same getter, so it stays independent of the session time zone: both sides decode through the
 * same path, and a mismatch means the buffer and the database disagree.
 */
@ParameterizedClass
@MethodSource("data")
public class UpdatableRowBufferCodecTest extends BaseTest4 {

  private static final BiPredicate<Object, Object> EQUALS = Object::equals;
  private static final BiPredicate<BigDecimal, BigDecimal> NUMERIC_EQ = (a, b) -> a.compareTo(b) == 0;
  private static final BiPredicate<OffsetTime, OffsetTime> OFFSET_TIME_EQ = OffsetTime::isEqual;
  private static final BiPredicate<OffsetDateTime, OffsetDateTime> OFFSET_DT_EQ = OffsetDateTime::isEqual;

  public UpdatableRowBufferCodecTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @BeforeAll
  static void createTables() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.createTable(con, "rowbufcodec",
          "id int primary key, b boolean, d date, t time, ttz timetz, "
              + "ts timestamp, tstz timestamptz, i4 int4, num numeric");
    }
  }

  @AfterAll
  static void dropTables() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.dropTable(con, "rowbufcodec");
    }
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.execute(con, "TRUNCATE rowbufcodec");
    TestUtil.execute(con, "INSERT INTO rowbufcodec (id) VALUES (1)");
  }

  @Test
  public void bool() throws SQLException {
    assertBufferMatchesDb("b", Boolean.TRUE, Boolean.class, EQUALS);
    assertBufferMatchesDb("b", Boolean.FALSE, Boolean.class, EQUALS);
  }

  @Test
  public void date() throws SQLException {
    assertBufferMatchesDb("d", LocalDate.of(2024, 1, 31), LocalDate.class, EQUALS);
    assertBufferMatchesDb("d", Date.valueOf("1999-12-31"), LocalDate.class, EQUALS);
  }

  @Test
  public void time() throws SQLException {
    assertBufferMatchesDb("t", LocalTime.of(12, 34, 56, 123_456_000), LocalTime.class, EQUALS);
    assertBufferMatchesDb("t", Time.valueOf("01:02:03"), LocalTime.class, EQUALS);
  }

  @Test
  public void timetz() throws SQLException {
    OffsetTime value = OffsetTime.of(LocalTime.of(12, 34, 56, 123_456_000), ZoneOffset.ofHours(-8));
    assertBufferMatchesDb("ttz", value, OffsetTime.class, OFFSET_TIME_EQ);
    assertBufferMatchesDb("ttz", Time.valueOf("01:02:03"), OffsetTime.class, OFFSET_TIME_EQ);
  }

  @Test
  public void timestamp() throws SQLException {
    assertBufferMatchesDb("ts", LocalDateTime.of(2024, 1, 31, 12, 34, 56, 123_456_000),
        LocalDateTime.class, EQUALS);
    assertBufferMatchesDb("ts", Timestamp.valueOf("1999-12-31 23:58:57.123456"),
        LocalDateTime.class, EQUALS);
  }

  @Test
  public void timestamptz() throws SQLException {
    OffsetDateTime value =
        OffsetDateTime.of(LocalDateTime.of(2024, 1, 31, 12, 34, 56, 123_456_000), ZoneOffset.ofHours(5));
    assertBufferMatchesDb("tstz", value, OffsetDateTime.class, OFFSET_DT_EQ);
    assertBufferMatchesDb("tstz", Timestamp.valueOf("1999-12-31 23:58:57.123456"),
        OffsetDateTime.class, OFFSET_DT_EQ);
  }

  @Test
  public void int4() throws SQLException {
    assertBufferMatchesDb("i4", 42, Integer.class, EQUALS);
  }

  @Test
  public void numeric() throws SQLException {
    assertBufferMatchesDb("num", new BigDecimal("12345.678"), BigDecimal.class, NUMERIC_EQ);
    // Loose class: an Integer routed to a numeric column must encode into the row buffer the same
    // value the database stores, so the buffer and the bind path agree.
    assertBufferMatchesDb("num", 42, BigDecimal.class, NUMERIC_EQ);
  }

  @Test
  public void insertRowMatrix() throws SQLException {
    OffsetTime ttz = OffsetTime.of(LocalTime.of(1, 2, 3, 456_000_000), ZoneOffset.ofHours(-8));
    OffsetDateTime tstz =
        OffsetDateTime.of(LocalDateTime.of(2024, 6, 1, 1, 2, 3, 456_000_000), ZoneOffset.ofHours(5));
    LocalDateTime ts = LocalDateTime.of(2024, 6, 1, 1, 2, 3, 456_000_000);
    LocalDate d = LocalDate.of(2024, 6, 1);
    LocalTime t = LocalTime.of(1, 2, 3, 456_000_000);

    try (Statement st = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE);
        ResultSet rs = st.executeQuery(
            "SELECT id, b, d, t, ttz, ts, tstz, i4, num FROM rowbufcodec")) {
      rs.moveToInsertRow();
      rs.updateInt("id", 2);
      rs.updateBoolean("b", true);
      rs.updateObject("d", d);
      rs.updateObject("t", t);
      rs.updateObject("ttz", ttz);
      rs.updateObject("ts", ts);
      rs.updateObject("tstz", tstz);
      rs.updateObject("i4", 7);
      rs.updateObject("num", new BigDecimal("3.14"));
      rs.insertRow();
    }

    try (Statement st = con.createStatement();
        ResultSet rs = st.executeQuery(
            "SELECT b, d, t, ttz, ts, tstz, i4, num FROM rowbufcodec WHERE id = 2")) {
      assertTrue(rs.next());
      assertTrue(rs.getBoolean(1));
      assertTrue(EQUALS.test(d, rs.getObject(2, LocalDate.class)));
      assertTrue(EQUALS.test(t, rs.getObject(3, LocalTime.class)));
      assertTrue(OFFSET_TIME_EQ.test(ttz, rs.getObject(4, OffsetTime.class)));
      assertTrue(EQUALS.test(ts, rs.getObject(5, LocalDateTime.class)));
      assertTrue(OFFSET_DT_EQ.test(tstz, rs.getObject(6, OffsetDateTime.class)));
      assertTrue(EQUALS.test(7, rs.getObject(7, Integer.class)));
      assertTrue(NUMERIC_EQ.test(new BigDecimal("3.14"), rs.getObject(8, BigDecimal.class)));
    }
  }

  private <T> void assertBufferMatchesDb(String column, Object update, Class<T> readAs,
      BiPredicate<? super T, ? super T> eq) throws SQLException {
    T buffer;
    try (Statement st = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE);
        ResultSet rs =
            st.executeQuery("SELECT id, " + column + " FROM rowbufcodec WHERE id = 1")) {
      assertTrue(rs.next(), "rowbufcodec must contain the seeded row with id = 1");
      rs.updateObject(column, update);
      rs.updateRow();
      buffer = rs.getObject(column, readAs);
    }

    T db;
    try (Statement st = con.createStatement();
        ResultSet rs = st.executeQuery("SELECT " + column + " FROM rowbufcodec WHERE id = 1")) {
      assertTrue(rs.next());
      db = rs.getObject(1, readAs);
    }

    assertNotNull(buffer, () -> "row buffer value must not be null for column " + column);
    assertNotNull(db, () -> "database value must not be null for column " + column);
    assertTrue(eq.test(buffer, db),
        () -> "row buffer " + buffer + " must match database " + db + " for column " + column
            + " after updateObject(" + update + ")");
  }
}
