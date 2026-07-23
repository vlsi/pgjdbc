/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.coercion.CoercionOutcome;
import org.postgresql.fuzzkit.coercion.OutcomeContract;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;
import org.postgresql.fuzzkit.coercion.ScalarDescriptor;
import org.postgresql.fuzzkit.coercion.WriteCoercions;
import org.postgresql.fuzzkit.coercion.WriteCoercions.Surface;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Pins the {@code UPDATABLE_RESULT_SET} surface of the write-coercion model
 * ({@link WriteCoercions}) on a live server: one deterministic run over every populated
 * {@code (oid, class)} cell, in both wire formats, instead of a coverage-guided fuzzer -- the matrix
 * is finite and an {@code updateRow()} round trip is far too slow for Zest.
 *
 * <p>For each cell the test seeds a NULL column, drives {@code updateObject} + {@code updateRow()},
 * and checks the outcome against the registry: an accepted cell must succeed with the row buffer
 * agreeing with a fresh {@code SELECT}, a refused cell must raise the registry's SQLState, and
 * <em>every</em> failure must leave the database row untouched (the staging invariant -- the row
 * buffer encodes before any statement reaches the server).
 *
 * <p>{@link #KNOWN_BIND_DIVERGENCES} lists the cells where the current structural
 * {@code PgPreparedStatement.setObject} bind leg diverges from the codec model: the codec accepts the
 * class, but {@code setObject} either cannot infer a type for it or binds it under its natural
 * parameter type, which the server cannot assignment-cast to the column type. Each entry is a
 * ratchet: migrating the scalar setters onto the codecs removes it, and this test fails when an entry
 * starts passing so the list cannot go stale.
 */
@ParameterizedClass
@MethodSource("data")
public class UpdatableResultSetWriteCoercionTest extends BaseTest4 {

  private static final String TABLE = "urs_write_matrix";

  /** One column per write-populated OID: the column name, its DDL type, and a parseable String sample. */
  private static final class ColumnSpec {
    final String name;
    final String ddlType;
    final String stringSample;

    ColumnSpec(String name, String ddlType, String stringSample) {
      this.name = name;
      this.ddlType = ddlType;
      this.stringSample = stringSample;
    }
  }

  private static final Map<Integer, ColumnSpec> COLUMNS = buildColumns();
  private static final Map<Class<?>, Object> CLASS_SAMPLES = buildClassSamples();
  private static final Map<String, String> KNOWN_BIND_DIVERGENCES = buildKnownBindDivergences();
  private static final Set<String> KNOWN_VALUE_DIVERGENCES = buildKnownValueDivergences();

  public UpdatableResultSetWriteCoercionTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  private static Map<Integer, ColumnSpec> buildColumns() {
    Map<Integer, ColumnSpec> m = new LinkedHashMap<>();
    m.put(Oid.INT2, new ColumnSpec("c_int2", "int2", "42"));
    m.put(Oid.INT4, new ColumnSpec("c_int4", "int4", "42"));
    m.put(Oid.INT8, new ColumnSpec("c_int8", "int8", "42"));
    m.put(Oid.OID, new ColumnSpec("c_oid", "oid", "42"));
    m.put(Oid.FLOAT4, new ColumnSpec("c_float4", "float4", "1.5"));
    m.put(Oid.FLOAT8, new ColumnSpec("c_float8", "float8", "1.5"));
    m.put(Oid.NUMERIC, new ColumnSpec("c_numeric", "numeric", "42.25"));
    m.put(Oid.TEXT, new ColumnSpec("c_text", "text", "hello"));
    m.put(Oid.VARCHAR, new ColumnSpec("c_varchar", "varchar", "hello"));
    // No typmod on bpchar: an unconstrained bpchar does not blank-pad, so the buffer-vs-database
    // comparison stays a codec property instead of a server-side padding effect.
    m.put(Oid.BPCHAR, new ColumnSpec("c_bpchar", "bpchar", "hello"));
    m.put(Oid.NAME, new ColumnSpec("c_name", "name", "hello"));
    m.put(Oid.BOOL, new ColumnSpec("c_bool", "bool", "true"));
    m.put(Oid.BYTEA, new ColumnSpec("c_bytea", "bytea", "\\x010203"));
    m.put(Oid.DATE, new ColumnSpec("c_date", "date", "2024-01-31"));
    m.put(Oid.TIME, new ColumnSpec("c_time", "time", "12:34:56"));
    m.put(Oid.TIMETZ, new ColumnSpec("c_timetz", "timetz", "12:34:56+03"));
    m.put(Oid.TIMESTAMP, new ColumnSpec("c_timestamp", "timestamp", "2024-01-31 12:34:56"));
    m.put(Oid.TIMESTAMPTZ, new ColumnSpec("c_timestamptz", "timestamptz", "2024-01-31 12:34:56+03"));
    return m;
  }

  private static Map<Class<?>, Object> buildClassSamples() {
    Map<Class<?>, Object> m = new HashMap<>();
    m.put(Integer.class, 42);
    m.put(Long.class, 42L);
    m.put(Short.class, (short) 42);
    m.put(Byte.class, (byte) 7);
    m.put(Boolean.class, Boolean.TRUE);
    m.put(Float.class, 1.5f);
    m.put(Double.class, 2.5);
    m.put(BigDecimal.class, new BigDecimal("42"));
    m.put(BigInteger.class, BigInteger.valueOf(42));
    m.put(byte[].class, new byte[]{1, 2, 3});
    m.put(Date.class, Date.valueOf("2024-01-31"));
    m.put(Time.class, Time.valueOf("12:34:56"));
    m.put(Timestamp.class, Timestamp.valueOf("2024-01-31 12:34:56"));
    m.put(java.util.Date.class,
        new java.util.Date(Timestamp.valueOf("2024-01-31 12:34:56").getTime()));
    m.put(LocalDate.class, LocalDate.of(2024, 1, 31));
    m.put(LocalTime.class, LocalTime.of(12, 34, 56));
    m.put(OffsetTime.class, OffsetTime.of(LocalTime.of(12, 34, 56), ZoneOffset.ofHours(3)));
    m.put(LocalDateTime.class, LocalDateTime.of(2024, 1, 31, 12, 34, 56));
    m.put(OffsetDateTime.class,
        OffsetDateTime.of(LocalDateTime.of(2024, 1, 31, 12, 34, 56), ZoneOffset.ofHours(3)));
    m.put(ZonedDateTime.class,
        ZonedDateTime.of(LocalDateTime.of(2024, 1, 31, 12, 34, 56), ZoneOffset.ofHours(3)));
    m.put(Instant.class, Instant.parse("2024-01-31T12:34:56Z"));
    m.put(UUID.class, UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"));
    return m;
  }

  /**
   * Cells where the current structural bind leg refuses a class the codec model accepts, keyed
   * {@code "<ddlType>:<class label>"} with the SQLState the cell raises today, identically in both
   * wire formats. Every entry is a ratchet the scalar-setter codec migration removes: the test fails
   * when an entry starts succeeding or changes its state.
   */
  private static Map<String, String> buildKnownBindDivergences() {
    Map<String, String> m = new HashMap<>();
    // setBoolean binds a bool parameter, and PostgreSQL has no bool -> number assignment cast;
    // the codec maps Boolean to 0/1.
    divergence(m, PSQLState.DATATYPE_MISMATCH,
        "int2:Boolean", "int4:Boolean", "int8:Boolean", "numeric:Boolean",
        "float4:Boolean", "float8:Boolean");
    // Under the default stringtype=varchar a String binds as a varchar parameter, and assignment
    // coercion via I/O exists only INTO the string category, so every non-string column refuses;
    // the codec parses the String as the column type (stringtype=unspecified would too).
    divergence(m, PSQLState.DATATYPE_MISMATCH,
        "int2:String", "int4:String", "int8:String", "oid:String",
        "float4:String", "float8:String", "numeric:String", "bool:String", "bytea:String",
        "date:String", "time:String", "timetz:String", "timestamp:String", "timestamptz:String");
    // setFloat/setDouble/setNumber bind float and numeric parameters, and PostgreSQL has no
    // float/numeric -> oid assignment cast; the codec widens any Number through longValue.
    divergence(m, PSQLState.DATATYPE_MISMATCH,
        "oid:Float", "oid:Double", "oid:BigDecimal", "oid:BigInteger");
    // setDate/setTime bind their text form with an unspecified OID, so the server parses a date
    // literal as a time (and vice versa) and refuses the mismatched text; the codec extracts the
    // date or time-of-day part of the value.
    divergence(m, PSQLState.BAD_DATETIME_FORMAT,
        "date:Time", "time:Date", "timetz:Date", "timestamp:Time", "timestamptz:Time");
    // setObject cannot infer a type for a plain java.util.Date; the temporal codecs accept it via
    // their java.util.Date catch-all. The date/timestamp/timestamptz variants of this cell are
    // OK_OR_COERCE, and 07006 sits in the write value-level state set, so only the OK time cells
    // surface the divergence.
    divergence(m, PSQLState.INVALID_PARAMETER_TYPE,
        "time:java.util.Date", "timetz:java.util.Date");
    return m;
  }

  /**
   * Cells where both legs accept the value but disagree on it, so the row buffer and the database
   * hold different values today, identically in both wire formats. Same ratchet contract as
   * {@link #KNOWN_BIND_DIVERGENCES}.
   */
  private static Set<String> buildKnownValueDivergences() {
    Set<String> s = new HashSet<>();
    // The codec truncates a Float through longValue; the bind leg sends a float4 parameter and the
    // server cast rounds. 1.5 truncates to 1 and rounds to 2. (The Double sample 2.5 happens to
    // truncate and round to the same value, so the Double cells do not surface the same gap.)
    s.add("int2:Float");
    s.add("int4:Float");
    s.add("int8:Float");
    // The codec keeps the local timestamp and drops the offset; the bind leg sends timestamptz and
    // the server conversion shifts the instant into the session time zone.
    s.add("timestamp:OffsetDateTime");
    s.add("timestamp:ZonedDateTime");
    // The codec stringifies Boolean to "true"; the bind leg's bool parameter reaches name through
    // I/O coercion (boolout), storing "t". text/varchar/bpchar agree because bool -> text is a
    // registered cast that also spells "true".
    s.add("name:Boolean");
    return s;
  }

  private static void divergence(Map<String, String> m, PSQLState state, String... cells) {
    for (String cell : cells) {
      m.put(cell, state.getState());
    }
  }

  @BeforeAll
  static void createTables() throws Exception {
    StringJoiner ddl = new StringJoiner(", ");
    ddl.add("id int primary key");
    for (ColumnSpec col : COLUMNS.values()) {
      ddl.add(col.name + " " + col.ddlType);
    }
    try (Connection con = TestUtil.openDB()) {
      TestUtil.createTable(con, TABLE, ddl.toString());
    }
  }

  @AfterAll
  static void dropTables() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.dropTable(con, TABLE);
    }
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.execute(con, "TRUNCATE " + TABLE);
    TestUtil.execute(con, "INSERT INTO " + TABLE + " (id) VALUES (1)");
  }

  @Test
  public void updatableResultSetSurfaceMatchesWriteCoercions() throws SQLException {
    List<String> failures = new ArrayList<>();
    int cells = 0;
    for (ScalarDescriptor descriptor : PgTypeDescriptors.scalars()) {
      Set<Class<?>> accepted = descriptor.acceptedClasses();
      if (accepted.isEmpty()) {
        continue;
      }
      int oid = descriptor.oid();
      ColumnSpec col = COLUMNS.get(oid);
      if (col == null) {
        failures.add("no column spec for write-populated OID " + oid
            + "; add it to COLUMNS so the matrix stays complete");
        continue;
      }
      List<Class<?>> classes = new ArrayList<>(accepted);
      if (!accepted.contains(UUID.class)) {
        // One off-matrix probe per type: an unlisted class must hit the type's default-deny.
        classes.add(UUID.class);
      }
      classes.sort(Comparator.comparing(Class::getName));
      for (Class<?> cls : classes) {
        runCell(oid, col, cls, failures);
        cells++;
      }
    }
    assertTrue(cells > 0, "the write-coercion registry must populate at least one scalar type");
    if (!failures.isEmpty()) {
      fail(failures.size() + " cell(s) diverged:\n" + String.join("\n", failures));
    }
  }

  private void runCell(int oid, ColumnSpec col, Class<?> cls, List<String> failures)
      throws SQLException {
    Object value = cls == String.class ? col.stringSample : CLASS_SAMPLES.get(cls);
    // java.sql.Date and java.util.Date must not collide on the simple name.
    String label = cls == java.util.Date.class ? "java.util.Date" : cls.getSimpleName();
    String cell = col.ddlType + ":" + label;
    if (value == null) {
      failures.add(cell + ": no sample value for an accepted class; add it to CLASS_SAMPLES");
      return;
    }
    CoercionOutcome expected =
        WriteCoercions.encode(Surface.UPDATABLE_RESULT_SET, oid, cls);
    String knownState = KNOWN_BIND_DIVERGENCES.get(cell);

    TestUtil.execute(con, "UPDATE " + TABLE + " SET " + col.name + " = NULL WHERE id = 1");
    try (Statement st = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE);
        ResultSet rs = st.executeQuery(
            "SELECT id, " + col.name + " FROM " + TABLE + " WHERE id = 1")) {
      assertTrue(rs.next(), TABLE + " must contain the seeded row with id = 1");
      rs.updateObject(col.name, value);
      try {
        rs.updateRow();
        if (knownState != null) {
          failures.add(cell + ": listed as a known bind divergence (" + knownState
              + ") but the update succeeded; remove the entry");
        } else if (!OutcomeContract.allowsReturn(expected)) {
          failures.add(cell + ": the registry says " + expected + " but updateRow() succeeded");
        } else {
          Object buffer = rs.getObject(col.name);
          Object db = freshSelect(col);
          boolean agree = valuesEqual(buffer, db);
          if (KNOWN_VALUE_DIVERGENCES.contains(cell)) {
            if (agree) {
              failures.add(cell + ": listed as a known value divergence but the row buffer agrees"
                  + " with the database; remove the entry");
            }
          } else if (!agree) {
            failures.add(cell + ": row buffer [" + render(buffer) + "] disagrees with database ["
                + render(db) + "] after updateObject(" + render(value) + ")");
          }
        }
      } catch (SQLException e) {
        String state = e.getSQLState();
        Object db = freshSelect(col);
        if (db != null) {
          failures.add(cell + ": updateRow() failed (" + state
              + ") but the database row changed to [" + render(db)
              + "]; a failure must fire before the server round trip");
        }
        if (knownState != null) {
          if (!knownState.equals(state)) {
            failures.add(cell + ": the known bind divergence expects SQLState " + knownState
                + " but got " + state + " (" + e.getMessage() + ")");
          }
        } else if (!OutcomeContract.matchesRefusal(expected, state, OutcomeContract.Direction.WRITE)) {
          failures.add(cell + ": the registry says " + expected + " but updateRow() raised SQLState "
              + state + " (" + e.getMessage() + ")");
        }
      }
    }
  }

  private @Nullable Object freshSelect(ColumnSpec col) throws SQLException {
    try (Statement st = con.createStatement();
        ResultSet rs = st.executeQuery(
            "SELECT " + col.name + " FROM " + TABLE + " WHERE id = 1")) {
      assertTrue(rs.next());
      return rs.getObject(1);
    }
  }

  /** Value equality across the buffer and the fresh read: BigDecimal by value, arrays by content. */
  private static boolean valuesEqual(@Nullable Object a, @Nullable Object b) {
    if (a instanceof BigDecimal && b instanceof BigDecimal) {
      return ((BigDecimal) a).compareTo((BigDecimal) b) == 0;
    }
    return Objects.deepEquals(a, b);
  }

  private static String render(@Nullable Object value) {
    if (value instanceof byte[]) {
      StringBuilder sb = new StringBuilder("0x");
      for (byte b : (byte[]) value) {
        sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
      }
      return sb.toString();
    }
    return String.valueOf(value);
  }
}
