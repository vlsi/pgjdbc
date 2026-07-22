/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.PGResultSetMetaData;
import org.postgresql.core.Field;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * {@code ResultSet.getObject(int, Map)} routes an {@code SQLData} mapping through the column's own
 * codec, not unconditionally through the composite codec. A composite column (the intended use of an
 * {@code SQLData} mapping) still decodes; a scalar column such as an enum, whose wire value is not a
 * composite record, refuses with {@code DATA_TYPE_MISMATCH} instead of being misparsed as a record.
 */
public class TypeMapSqlDataScalarTest extends BaseTest4 {

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createCompositeType(con, "s_labeled", "label varchar, n int");
    TestUtil.createEnumType(con, "s_color", "'red', 'green', 'blue'");
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropType(con, "s_color");
    TestUtil.dropType(con, "s_labeled");
    super.tearDown();
  }

  @Test
  public void compositeMappedToSqlDataDecodesText() throws SQLException {
    Map<String, Class<?>> map = new HashMap<>();
    map.put("s_labeled", Labeled.class);

    try (Statement st = con.createStatement();
         ResultSet rs = st.executeQuery("SELECT ROW('hello', 7)::s_labeled")) {
      assertTrue(rs.next());
      Labeled labeled = assertInstanceOf(Labeled.class, rs.getObject(1, map));
      assertEquals("hello", labeled.label);
      assertEquals(7, labeled.n);
    }
  }

  @Test
  public void compositeMappedToSqlDataDecodesBinary() throws SQLException {
    // The binary composite must decode through getBinaryCodec (CompositeCodec), not a text round-trip.
    Properties props = new Properties();
    PGProperty.BINARY_TRANSFER_ENABLE.set(props, "*");
    PGProperty.PREPARE_THRESHOLD.set(props, -1);
    try (Connection binCon = TestUtil.openDB(props)) {
      assumeTrue(binCon.unwrap(PgConnection.class).getPreferQueryMode() != PreferQueryMode.SIMPLE,
          "binary transfer needs the extended protocol");

      Map<String, Class<?>> map = new HashMap<>();
      map.put("s_labeled", Labeled.class);

      try (PreparedStatement ps = binCon.prepareStatement("SELECT ROW('hello', 7)::s_labeled");
           ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(Field.BINARY_FORMAT, ((PGResultSetMetaData) rs.getMetaData()).getFormat(1),
            "composite must come back in binary for this test to exercise the binary codec path");
        Labeled labeled = assertInstanceOf(Labeled.class, rs.getObject(1, map));
        assertEquals("hello", labeled.label);
        assertEquals(7, labeled.n);
      }
    }
  }

  @Test
  public void enumMappedToSqlDataRefuses() throws SQLException {
    // An enum is scalar, so its wire value is not a composite record. Mapping it to SQLData must not
    // parse the value as a composite; the enum codec refuses the SQLData target instead.
    Map<String, Class<?>> map = new HashMap<>();
    map.put("s_color", Labeled.class);

    try (Statement st = con.createStatement();
         ResultSet rs = st.executeQuery("SELECT 'green'::s_color")) {
      assertTrue(rs.next());
      SQLException ex = assertThrows(SQLException.class, () -> rs.getObject(1, map));
      assertEquals(PSQLState.DATA_TYPE_MISMATCH.getState(), ex.getSQLState(),
          "an enum mapped to SQLData must be refused, not misparsed as a composite");
    }
  }

  /** A composite value read through {@link SQLInput}. */
  public static class Labeled implements SQLData {
    String label = "";
    int n;

    @Override
    public String getSQLTypeName() {
      return "s_labeled";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      label = stream.readString();
      n = stream.readInt();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeString(label);
      stream.writeInt(n);
    }
  }
}
