/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;

class HstoreCodecTest {

  private final HstoreCodec codec = HstoreCodec.INSTANCE;

  // An empty hstore ('') is a non-NULL empty map, not SQL NULL (NULL never reaches decode). The codec
  // used to short-circuit the empty text to null, which read back as a NULL value instead of an empty map.
  @Test
  void decodeText_empty_isEmptyMap() throws SQLException {
    Object result = codec.decodeText("", null, null);
    assertNotNull(result, "empty hstore text must decode to an empty map, not null");
    assertEquals(0, ((Map<?, ?>) result).size());
  }

  @Test
  void decodeText_pairs() throws SQLException {
    Object result = codec.decodeText("\"a\"=>\"1\", \"b\"=>\"2\"", null, null);
    @SuppressWarnings("unchecked")
    Map<String, String> map = (Map<String, String>) result;
    assertEquals("1", map.get("a"));
    assertEquals("2", map.get("b"));
    assertEquals(2, map.size());
  }

  @Test
  void decodeText_nullValue() throws SQLException {
    Object result = codec.decodeText("\"a\"=>NULL", null, null);
    @SuppressWarnings("unchecked")
    Map<String, String> map = (Map<String, String>) result;
    assertEquals(1, map.size());
    assertNull(map.get("a"));
  }
}
