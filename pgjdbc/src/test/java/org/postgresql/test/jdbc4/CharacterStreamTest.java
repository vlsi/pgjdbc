/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.io.StringReader;
import java.sql.PreparedStatement;
import java.sql.SQLFeatureNotSupportedException;

public class CharacterStreamTest extends BaseTest4 {

  private static final String TEST_TABLE_NAME = "charstream";
  private static final String TEST_COLUMN_NAME = "cs";

  private static final String _insert;
  private static final String _select;

  static {
    _insert = String.format("INSERT INTO %s (%s) VALUES (?)", TEST_TABLE_NAME, TEST_COLUMN_NAME);
    _select = String.format("SELECT %s FROM %s", TEST_COLUMN_NAME, TEST_TABLE_NAME);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTempTable(con, TEST_TABLE_NAME, "cs text");
  }

  private void insertStreamKnownIntLength(String data) throws Exception {
    PreparedStatement insertPS = con.prepareStatement(_insert);
    try {
      Reader reader = data != null ? new StringReader(data) : null;
      int length = data != null ? data.length() : 0;
      insertPS.setCharacterStream(1, reader, length);
      insertPS.executeUpdate();
    } finally {
      TestUtil.closeQuietly(insertPS);
    }
  }

  private void insertStreamKnownLongLength(String data) throws Exception {
    PreparedStatement insertPS = con.prepareStatement(_insert);
    try {
      Reader reader = data != null ? new StringReader(data) : null;
      long length = data != null ? data.length() : 0;
      try {
        insertPS.setCharacterStream(1, reader, length);
      } catch (SQLFeatureNotSupportedException e) {
        assumeTrue(false, "PreparedStatement.setCharacterStream(int, Reader, long) is not implemented");
      }
      insertPS.executeUpdate();
    } finally {
      TestUtil.closeQuietly(insertPS);
    }
  }

  private void insertStreamUnknownLength(String data) throws Exception {
    PreparedStatement insertPS = con.prepareStatement(_insert);
    try {
      Reader reader = data != null ? new StringReader(data) : null;
      insertPS.setCharacterStream(1, reader);
      insertPS.executeUpdate();
    } finally {
      TestUtil.closeQuietly(insertPS);
    }
  }

  private void validateContent(String data) throws Exception {
    String actualData = TestUtil.queryForString(con, _select);
    assertEquals(data, actualData, "Sent and received data are not the same");
  }

  private static String getTestData(int size) {
    StringBuilder buf = new StringBuilder(size);
    String s = "This is a test string.\n";
    int slen = s.length();
    int len = 0;

    while ((len + slen) < size) {
      buf.append(s);
      len += slen;
    }

    while (len < size) {
      buf.append('.');
      len++;
    }

    return buf.toString();
  }

  @Test
  public void testKnownIntLengthNull() throws Exception {
    String data = null;
    insertStreamKnownIntLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownLongLengthNull() throws Exception {
    String data = null;
    insertStreamKnownLongLength(data);
    validateContent(data);
  }

  @Test
  public void testUnknownLengthNull() throws Exception {
    String data = null;
    insertStreamUnknownLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownIntLengthEmpty() throws Exception {
    String data = "";
    insertStreamKnownIntLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownLongLengthEmpty() throws Exception {
    String data = "";
    insertStreamKnownLongLength(data);
    validateContent(data);
  }

  @Test
  public void testUnknownLengthEmpty() throws Exception {
    String data = "";
    insertStreamUnknownLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownIntLength2Kb() throws Exception {
    String data = getTestData(2 * 1024);
    insertStreamKnownIntLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownLongLength2Kb() throws Exception {
    String data = getTestData(2 * 1024);
    insertStreamKnownLongLength(data);
    validateContent(data);
  }

  @Test
  public void testUnknownLength2Kb() throws Exception {
    String data = getTestData(2 * 1024);
    insertStreamUnknownLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownIntLength10Kb() throws Exception {
    String data = getTestData(10 * 1024);
    insertStreamKnownIntLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownLongLength10Kb() throws Exception {
    String data = getTestData(10 * 1024);
    insertStreamKnownLongLength(data);
    validateContent(data);
  }

  @Test
  public void testUnknownLength10Kb() throws Exception {
    String data = getTestData(10 * 1024);
    insertStreamUnknownLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownIntLength100Kb() throws Exception {
    String data = getTestData(100 * 1024);
    insertStreamKnownIntLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownLongLength100Kb() throws Exception {
    String data = getTestData(100 * 1024);
    insertStreamKnownLongLength(data);
    validateContent(data);
  }

  @Test
  public void testUnknownLength100Kb() throws Exception {
    String data = getTestData(100 * 1024);
    insertStreamUnknownLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownIntLength200Kb() throws Exception {
    String data = getTestData(200 * 1024);
    insertStreamKnownIntLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownLongLength200Kb() throws Exception {
    String data = getTestData(200 * 1024);
    insertStreamKnownLongLength(data);
    validateContent(data);
  }

  @Test
  public void testUnknownLength200Kb() throws Exception {
    String data = getTestData(200 * 1024);
    insertStreamUnknownLength(data);
    validateContent(data);
  }
}
