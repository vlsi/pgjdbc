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
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * After {@code updateRow()}/{@code insertRow()} the driver re-encodes the new value into the
 * in-memory row buffer so a later getter sees the update without a server round-trip. That buffer
 * encode funnels through {@code ColumnValueEncoder} over the codec registry -- the same
 * {@code (oid, class, format) -> wire} coercion the write-side fuzz oracle
 * ({@code org.postgresql.fuzzkit.coercion.WriteCoercions}) models for every write surface.
 *
 * <p>This test pins the {@code UPDATABLE_RESULT_SET} surface against that model across a broad type
 * matrix, in both the text and binary wire formats: for each accepted {@code (type, class)} cell the
 * freshly written row buffer must agree with what the database actually stored. The oracle reads the
 * buffer and a fresh {@code SELECT} of the same row with the same getter, so it stays independent of
 * the session time zone -- both sides decode through the same path, and a mismatch means the buffer
 * and the database disagree. Rejection cells are not exercised here: {@code updateRow()} binds to the
 * server before it refreshes the buffer, so a refused class fails on the bind path, not the codec
 * buffer encode; the offline writer fuzzer covers the refusal matrix.
 */
@ParameterizedClass
@MethodSource("data")
public class UpdatableRowBufferCodecTest extends BaseTest4 {

  private static final BiPredicate<Object, Object> EQUALS = Object::equals;
  private static final BiPredicate<BigDecimal, BigDecimal> NUMERIC_EQ = (a, b) -> a.compareTo(b) == 0;
  private static final BiPredicate<byte[], byte[]> BYTES_EQ = Arrays::equals;
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
          "id int primary key, b boolean, i2 int2, i4 int4, i8 int8, f4 float4, f8 float8, "
              + "num numeric, txt text, vc varchar(64), uid uuid, ba bytea, "
              + "d date, t time, ttz timetz, ts timestamp, tstz timestamptz");
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
  public void int2() throws SQLException {
    assertBufferMatchesDb("i2", (short) 12345, Short.class, EQUALS);
  }

  @Test
  public void int4() throws SQLException {
    assertBufferMatchesDb("i4", 42, Integer.class, EQUALS);
  }

  @Test
  public void int8() throws SQLException {
    assertBufferMatchesDb("i8", 9_000_000_000L, Long.class, EQUALS);
    // Loose class: an Integer widens to int8 on both the buffer and the bind path.
    assertBufferMatchesDb("i8", 42, Long.class, EQUALS);
  }

  @Test
  public void float4() throws SQLException {
    assertBufferMatchesDb("f4", 1.5f, Float.class, EQUALS);
  }

  @Test
  public void float8() throws SQLException {
    assertBufferMatchesDb("f8", 3.141592653589793, Double.class, EQUALS);
    // Loose class: a Float widens to float8 exactly for a value with a finite binary fraction.
    assertBufferMatchesDb("f8", 1.5f, Double.class, EQUALS);
  }

  @Test
  public void numeric() throws SQLException {
    assertBufferMatchesDb("num", new BigDecimal("12345.678"), BigDecimal.class, NUMERIC_EQ);
    // Loose class: an Integer routed to a numeric column must encode the same value the database
    // stores, so the buffer and the bind path agree.
    assertBufferMatchesDb("num", 42, BigDecimal.class, NUMERIC_EQ);
  }

  @Test
  public void text() throws SQLException {
    assertBufferMatchesDb("txt", "a text value with unicode ы", String.class, EQUALS);
  }

  @Test
  public void varchar() throws SQLException {
    assertBufferMatchesDb("vc", "varchar value", String.class, EQUALS);
  }

  @Test
  public void uuid() throws SQLException {
    assertBufferMatchesDb("uid", UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
        UUID.class, EQUALS);
  }

  @Test
  public void bytea() throws SQLException {
    assertBufferMatchesDb("ba", new byte[]{0, 1, 2, (byte) 0xff, 0x7f, (byte) 0x80},
        byte[].class, BYTES_EQ);
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
  public void insertRowMatrix() throws SQLException {
    OffsetTime ttz = OffsetTime.of(LocalTime.of(1, 2, 3, 456_000_000), ZoneOffset.ofHours(-8));
    OffsetDateTime tstz =
        OffsetDateTime.of(LocalDateTime.of(2024, 6, 1, 1, 2, 3, 456_000_000), ZoneOffset.ofHours(5));
    LocalDateTime ts = LocalDateTime.of(2024, 6, 1, 1, 2, 3, 456_000_000);
    LocalDate d = LocalDate.of(2024, 6, 1);
    LocalTime t = LocalTime.of(1, 2, 3, 456_000_000);
    UUID uid = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
    byte[] ba = {0, 1, 2, (byte) 0xff};

    try (Statement st = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE);
        ResultSet rs = st.executeQuery(
            "SELECT id, b, i2, i4, i8, f4, f8, num, txt, vc, uid, ba, d, t, ttz, ts, tstz "
                + "FROM rowbufcodec")) {
      rs.moveToInsertRow();
      rs.updateInt("id", 2);
      rs.updateBoolean("b", true);
      rs.updateObject("i2", (short) 7);
      rs.updateObject("i4", 8);
      rs.updateObject("i8", 9L);
      rs.updateObject("f4", 1.5f);
      rs.updateObject("f8", 2.5);
      rs.updateObject("num", new BigDecimal("3.14"));
      rs.updateObject("txt", "hello");
      rs.updateObject("vc", "world");
      rs.updateObject("uid", uid);
      rs.updateObject("ba", ba);
      rs.updateObject("d", d);
      rs.updateObject("t", t);
      rs.updateObject("ttz", ttz);
      rs.updateObject("ts", ts);
      rs.updateObject("tstz", tstz);
      rs.insertRow();
    }

    try (Statement st = con.createStatement();
        ResultSet rs = st.executeQuery(
            "SELECT b, i2, i4, i8, f4, f8, num, txt, vc, uid, ba, d, t, ttz, ts, tstz "
                + "FROM rowbufcodec WHERE id = 2")) {
      assertTrue(rs.next());
      assertTrue(rs.getBoolean(1));
      assertTrue(EQUALS.test((short) 7, rs.getObject(2, Short.class)));
      assertTrue(EQUALS.test(8, rs.getObject(3, Integer.class)));
      assertTrue(EQUALS.test(9L, rs.getObject(4, Long.class)));
      assertTrue(EQUALS.test(1.5f, rs.getObject(5, Float.class)));
      assertTrue(EQUALS.test(2.5, rs.getObject(6, Double.class)));
      assertTrue(NUMERIC_EQ.test(new BigDecimal("3.14"), rs.getObject(7, BigDecimal.class)));
      assertTrue(EQUALS.test("hello", rs.getObject(8, String.class)));
      assertTrue(EQUALS.test("world", rs.getObject(9, String.class)));
      assertTrue(EQUALS.test(uid, rs.getObject(10, UUID.class)));
      assertTrue(BYTES_EQ.test(ba, rs.getObject(11, byte[].class)));
      assertTrue(EQUALS.test(d, rs.getObject(12, LocalDate.class)));
      assertTrue(EQUALS.test(t, rs.getObject(13, LocalTime.class)));
      assertTrue(OFFSET_TIME_EQ.test(ttz, rs.getObject(14, OffsetTime.class)));
      assertTrue(EQUALS.test(ts, rs.getObject(15, LocalDateTime.class)));
      assertTrue(OFFSET_DT_EQ.test(tstz, rs.getObject(16, OffsetDateTime.class)));
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
