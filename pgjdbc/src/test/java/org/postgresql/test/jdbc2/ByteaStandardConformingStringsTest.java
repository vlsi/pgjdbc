/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.ByteBufferByteStreamWriter;
import org.postgresql.util.ByteStreamWriter;
import org.postgresql.util.PGobject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Fails when a {@code bytea} parameter does not survive a round trip on a server with
 * {@code standard_conforming_strings} off.
 *
 * <p>{@link PreferQueryMode#SIMPLE} writes the parameter into the SQL text as a hex literal, and
 * the hex format opens with a backslash. A server with {@code standard_conforming_strings} off
 * reads a backslash in a plain literal as an escape character, so the driver has to write an
 * escape string constant there. The plain form used to reach such a server as {@code '\x0001'},
 * where {@code \x00} is a NUL byte, and the server rejected the statement with
 * {@code invalid byte sequence for encoding "UTF8": 0x00}.</p>
 *
 * <p>Each test binds one parameter shape, because the driver formats each shape on its own path:
 * {@code byte[]}, an {@code InputStream}, a {@link ByteStreamWriter}, and a hex string in a
 * {@link PGobject}.</p>
 */
@ParameterizedClass
@MethodSource("data")
public class ByteaStandardConformingStringsTest extends BaseTest4 {

  /**
   * A NUL, a byte no UTF-8 sequence can start with, and the two characters an escape string
   * constant treats specially.
   */
  private static final byte[] DATA = {0x00, 0x01, (byte) 0xe6, 0x39, 0x37, (byte) 0xff, '\\', '\''};

  private final boolean standardConformingStrings;

  public ByteaStandardConformingStringsTest(PreferQueryMode preferQueryMode,
      boolean standardConformingStrings) {
    setPreferQueryMode(preferQueryMode);
    this.standardConformingStrings = standardConformingStrings;
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (PreferQueryMode preferQueryMode
        : new PreferQueryMode[]{PreferQueryMode.SIMPLE, PreferQueryMode.EXTENDED}) {
      for (boolean standardConformingStrings : new boolean[]{true, false}) {
        ids.add(new Object[]{preferQueryMode, standardConformingStrings});
      }
    }
    return ids;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    assumeTrue(standardConformingStrings
            || !TestUtil.haveMinimumServerVersion(con, ServerVersion.v19),
        "PostgreSQL 19 accepts standard_conforming_strings=on only");
    try (Statement stmt = con.createStatement()) {
      stmt.execute("SET standard_conforming_strings TO "
          + (standardConformingStrings ? "on" : "off"));
    }
    assertTrue(standardConformingStrings == TestUtil.getStandardConformingStrings(con),
        "the driver should have picked up the SET from the server's ParameterStatus message");
    TestUtil.createTempTable(con, "byteatest", "data bytea");
  }

  @Test
  public void setBytes() throws SQLException {
    try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO byteatest VALUES (?)")) {
      pstmt.setBytes(1, DATA);
      pstmt.executeUpdate();
    }
    assertArrayEquals(DATA, selectData());
  }

  @Test
  public void setBinaryStream() throws SQLException {
    try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO byteatest VALUES (?)")) {
      pstmt.setBinaryStream(1, new ByteArrayInputStream(DATA), DATA.length);
      pstmt.executeUpdate();
    }
    assertArrayEquals(DATA, selectData());
  }

  @Test
  public void setObjectByteStreamWriter() throws SQLException {
    try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO byteatest VALUES (?)")) {
      pstmt.setObject(1, new ByteBufferByteStreamWriter(ByteBuffer.wrap(DATA)));
      pstmt.executeUpdate();
    }
    assertArrayEquals(DATA, selectData());
  }

  @Test
  public void setObjectHexPGobject() throws SQLException {
    PGobject bytea = new PGobject();
    bytea.setType("bytea");
    bytea.setValue(toHexFormat(DATA));
    try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO byteatest VALUES (?)")) {
      pstmt.setObject(1, bytea);
      pstmt.executeUpdate();
    }
    assertArrayEquals(DATA, selectData());
  }

  private byte[] selectData() throws SQLException {
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT data FROM byteatest")) {
      assertTrue(rs.next(), "the insert should have left one row in byteatest");
      return rs.getBytes(1);
    }
  }

  private static String toHexFormat(byte[] data) {
    StringBuilder sb = new StringBuilder(2 + 2 * data.length);
    sb.append("\\x");
    for (byte b : data) {
      sb.append(Character.forDigit((b >> 4) & 0xf, 16));
      sb.append(Character.forDigit(b & 0xf, 16));
    }
    return sb.toString();
  }
}
