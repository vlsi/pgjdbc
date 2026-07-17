/*
 * Copyright (c) 2005, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.PGStatement;
import org.postgresql.core.BaseConnection;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.test.util.CountingSocketFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;

@ParameterizedClass
@MethodSource("data")
public class ParameterMetaDataTest extends BaseTest4 {
  private final CountingSocketFactory.Counters socketCounters = CountingSocketFactory.register();

  public ParameterMetaDataTest(BinaryMode binaryMode) {
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
      TestUtil.createTable(con, "parametertest",
          "a int4, b float8, c text, d point, e timestamp with time zone");
    }
  }

  @AfterAll
  static void dropTables() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.dropTable(con, "parametertest");
    }
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.SOCKET_FACTORY.set(props, CountingSocketFactory.class.getName());
    PGProperty.SOCKET_FACTORY_ARG.set(props, socketCounters.key());
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    assumeTrue(preferQueryMode != PreferQueryMode.SIMPLE, "simple protocol only does not support describe statement requests");
  }

  @Override
  protected void tearDown() throws SQLException {
    try {
      super.tearDown();
    } finally {
      CountingSocketFactory.unregister(socketCounters);
    }
  }

  @Test
  public void testParameterMD() throws SQLException {
    PreparedStatement pstmt =
        con.prepareStatement("SELECT a FROM parametertest WHERE b = ? AND c = ? AND d >^ ? ");
    ParameterMetaData pmd = pstmt.getParameterMetaData();

    assertEquals(3, pmd.getParameterCount());
    assertEquals(Types.DOUBLE, pmd.getParameterType(1));
    assertEquals("float8", pmd.getParameterTypeName(1));
    assertEquals("java.lang.Double", pmd.getParameterClassName(1));
    assertEquals(Types.VARCHAR, pmd.getParameterType(2));
    assertEquals("text", pmd.getParameterTypeName(2));
    assertEquals("java.lang.String", pmd.getParameterClassName(2));
    assertEquals(Types.OTHER, pmd.getParameterType(3));
    assertEquals("point", pmd.getParameterTypeName(3));
    assertEquals("org.postgresql.geometric.PGpoint", pmd.getParameterClassName(3));

    pstmt.close();
  }

  @Test
  public void testFailsOnBadIndex() throws SQLException {
    PreparedStatement pstmt =
        con.prepareStatement("SELECT a FROM parametertest WHERE b = ? AND c = ?");
    ParameterMetaData pmd = pstmt.getParameterMetaData();
    try {
      pmd.getParameterType(0);
      fail("Can't get parameter for index < 1.");
    } catch (SQLException sqle) {
    }
    try {
      pmd.getParameterType(3);
      fail("Can't get parameter for index 3 with only two parameters.");
    } catch (SQLException sqle) {
    }
  }

  // Make sure we work when mashing two queries into a single statement.
  @Test
  public void testMultiStatement() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "SELECT a FROM parametertest WHERE b = ? AND c = ? ; SELECT b FROM parametertest WHERE a = ?");
    ParameterMetaData pmd = pstmt.getParameterMetaData();

    assertEquals(3, pmd.getParameterCount());
    assertEquals(Types.DOUBLE, pmd.getParameterType(1));
    assertEquals("float8", pmd.getParameterTypeName(1));
    assertEquals(Types.VARCHAR, pmd.getParameterType(2));
    assertEquals("text", pmd.getParameterTypeName(2));
    assertEquals(Types.INTEGER, pmd.getParameterType(3));
    assertEquals("int4", pmd.getParameterTypeName(3));

    pstmt.close();

  }

  // Here we test that we can legally change the resolved type
  // from text to varchar with the complicating factor that there
  // is also an unknown parameter.
  //
  @Test
  public void testTypeChangeWithUnknown() throws SQLException {
    PreparedStatement pstmt =
        con.prepareStatement("SELECT a FROM parametertest WHERE c = ? AND e = ?");
    ParameterMetaData pmd = pstmt.getParameterMetaData();

    pstmt.setString(1, "Hi");
    pstmt.setTimestamp(2, new Timestamp(0L));

    ResultSet rs = pstmt.executeQuery();
    rs.close();
  }

  // https://github.com/pgjdbc/pgjdbc/issues/621: getParameterMetaData() used to issue a
  // network "describe" on every single call, even when the parameter types were already known.

  @Test
  public void testGetParameterMetaDataAvoidsRoundtripAcrossCalls() throws SQLException {
    try (PreparedStatement pstmt =
        con.prepareStatement("select /* avoidsRoundtripAcrossCalls */ ?::timestamp")) {
      long before = socketCounters.roundtrips.get();
      assertEquals(Types.TIMESTAMP, pstmt.getParameterMetaData().getParameterType(1));
      long afterFirst = socketCounters.roundtrips.get();
      assertEquals(1, afterFirst - before,
          "the first getParameterMetaData() call has nothing cached, so it must describe the statement");

      assertEquals(Types.TIMESTAMP, pstmt.getParameterMetaData().getParameterType(1));
      assertEquals(0, socketCounters.roundtrips.get() - afterFirst,
          "a repeated getParameterMetaData() call with the same (unspecified) parameter types "
              + "must reuse the cached describe result");
    }
  }

  @Test
  public void testGetParameterMetaDataAvoidsRoundtripAcrossPreparedStatements() throws SQLException {
    String sql = "select /* avoidsRoundtripAcrossPreparedStatements */ ?::timestamp";
    long before = socketCounters.roundtrips.get();
    try (PreparedStatement first = con.prepareStatement(sql)) {
      assertEquals(Types.TIMESTAMP, first.getParameterMetaData().getParameterType(1));
    }
    long afterFirst = socketCounters.roundtrips.get();
    assertEquals(1, afterFirst - before, "the very first describe of this SQL text must go to the network");

    // A fresh PreparedStatement for the identical SQL text, never executed -- this is the
    // common pattern of re-preparing the same SQL on every call (see pgjdbc issue #621:
    // applications rarely keep one PreparedStatement object alive; they re-prepare the same SQL
    // text every time, which pgjdbc recognizes and hands back the connection's pooled query
    // object for once the first statement is closed and its query is released back to the pool).
    try (PreparedStatement second = con.prepareStatement(sql)) {
      assertEquals(Types.TIMESTAMP, second.getParameterMetaData().getParameterType(1));
      assertEquals(0, socketCounters.roundtrips.get() - afterFirst,
          "a new PreparedStatement for the same, never-before-bound SQL should reuse the "
              + "connection-level describe cache");
    }
  }

  @Test
  public void testGetParameterMetaDataRedescribesOnTypeChange() throws SQLException {
    try (PreparedStatement pstmt = con.prepareStatement("select /* redescribesOnTypeChange */ ?")) {
      pstmt.setInt(1, 1);
      long before = socketCounters.roundtrips.get();
      assertEquals(Types.INTEGER, pstmt.getParameterMetaData().getParameterType(1));
      long afterFirst = socketCounters.roundtrips.get();
      assertEquals(1, afterFirst - before, "the first describe for this bind type must go to the network");

      pstmt.setLong(1, 1L);
      assertEquals(Types.BIGINT, pstmt.getParameterMetaData().getParameterType(1));
      assertEquals(1, socketCounters.roundtrips.get() - afterFirst,
          "changing the bound parameter type must invalidate the cached describe result and re-describe");
    }
  }

  @Test
  public void testGetParameterMetaDataSurvivesDeallocateAll() throws SQLException {
    BaseConnection baseConnection = con.unwrap(BaseConnection.class);
    // Disable the proactive DEALLOCATE ALL/DISCARD ALL detection, simulating a case where the
    // driver has no direct way of noticing that the server-side statement is gone (e.g. a
    // connection pooler resetting session state between logical checkouts).
    baseConnection.setFlushCacheOnDeallocate(false);
    try (PreparedStatement pstmt = con.prepareStatement("select /* survivesDeallocateAll */ ?")) {
      ((PGStatement) pstmt).setPrepareThreshold(1);
      pstmt.setInt(1, 1);
      pstmt.executeQuery().close(); // becomes a named, server-side prepared statement

      try (Statement plain = con.createStatement()) {
        plain.executeUpdate("DEALLOCATE ALL");
      }

      // Same bind type as before: the parameter types are still correct (DEALLOCATE ALL only
      // drops the server-side plan, it does not change any column/parameter type), so
      // getParameterMetaData() is entitled to keep trusting its cache -- this is the same
      // "consistent with itself" behavior already accepted for a concurrent DDL change, and it
      // is why no round trip -- and therefore no "prepared statement ... does not exist" error
      // -- happens here at all.
      long before = socketCounters.roundtrips.get();
      ParameterMetaData pmd = assertDoesNotThrow(pstmt::getParameterMetaData,
          "getParameterMetaData() must not fail in the face of a concurrently deallocated "
              + "server-side statement (pgjdbc issue #621)");
      assertEquals(Types.INTEGER, pmd.getParameterType(1));
      assertEquals(0, socketCounters.roundtrips.get() - before,
          "the cached parameter types are still valid, so no network round trip -- and thus no "
              + "'prepared statement does not exist' error -- should occur here");

      // Now force a real describe by rebinding a different type. The stale server-side name is
      // still considered locally valid (types+epoch unchanged), so sendParse() must detect the
      // mismatch and transparently re-parse with a fresh statement, rather than attempting to
      // reuse (and fail against) the already-deallocated name.
      pstmt.setLong(1, 1L);
      ParameterMetaData pmd2 = assertDoesNotThrow(pstmt::getParameterMetaData,
          "changing the bound type after DEALLOCATE ALL must still re-describe successfully");
      assertEquals(Types.BIGINT, pmd2.getParameterType(1));
    } finally {
      baseConnection.setFlushCacheOnDeallocate(true);
    }
  }
}
