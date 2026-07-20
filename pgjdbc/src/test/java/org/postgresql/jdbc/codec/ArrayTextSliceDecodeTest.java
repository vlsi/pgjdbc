/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TypeName;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.OfflineCodecs;
import org.postgresql.jdbc.PgType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.CharBuffer;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * {@link ArrayCodec#decodeText} must agree with itself whether it is handed a {@code String} or a
 * {@link CharBuffer} view over a slice of a larger buffer.
 *
 * <p>An array nested in a composite or another array is parsed straight off the parent's borrowed
 * buffer, so every index the decoder reads is relative to an offset the whole-string form never
 * sees. These assertions pad the literal on both sides, which turns an unadjusted index into a
 * wrong answer rather than a silently passing one.</p>
 */
class ArrayTextSliceDecodeTest {

  private static final PgType INT4_ARRAY = new PgType(
      TypeName.of("pg_catalog", "_int4"), "int4[]", Oid.INT4_ARRAY, 'b', 'A', -1,
      Oid.INT4, 0, 0);

  private static final PgType TEXT_ARRAY = new PgType(
      TypeName.of("pg_catalog", "_text"), "text[]", Oid.TEXT_ARRAY, 'b', 'A', -1,
      Oid.TEXT, 0, 0);

  private static CodecContext offline() {
    return OfflineCodecs.builder().type(INT4_ARRAY).type(TEXT_ARRAY).build();
  }

  /** Wraps {@code literal} in filler so a slice read from offset 0 decodes the wrong characters. */
  private static char[] padded(String literal) {
    char[] buf = new char[7 + literal.length() + 5];
    Arrays.fill(buf, '#');
    literal.getChars(0, literal.length(), buf, 7);
    return buf;
  }

  private static Object decodeSlice(PgType type, String literal) throws SQLException {
    return ArrayCodec.INSTANCE.decodeText(
        CharBuffer.wrap(padded(literal), 7, literal.length()), type, offline());
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{1}", "{1,2,3}", "{-1,0,2147483647}", "{{1,2},{3,4}}",
      "{{{1},{2}},{{3},{4}}}"})
  void int4ArraySliceMatchesWholeString(String literal) throws SQLException {
    Object whole = ArrayCodec.INSTANCE.decodeText(literal, INT4_ARRAY, offline());
    Object slice = decodeSlice(INT4_ARRAY, literal);
    assertEquals(Arrays.deepToString(new Object[]{whole}), Arrays.deepToString(new Object[]{slice}),
        () -> "slice decode of " + literal);
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{a}", "{a,b}", "{\"a,b\",c}", "{\"}\",\"{\"}", "{NULL,a}",
      "{\"\"}", "{\" padded \"}", "{\"quote\\\"inside\"}"})
  void textArraySliceMatchesWholeString(String literal) throws SQLException {
    Object whole = ArrayCodec.INSTANCE.decodeText(literal, TEXT_ARRAY, offline());
    Object slice = decodeSlice(TEXT_ARRAY, literal);
    assertArrayEquals((Object[]) whole, (Object[]) slice, () -> "slice decode of " + literal);
  }

  @Test
  void viewStopsAtItsLimitRatherThanRunningIntoTrailingBuffer() throws SQLException {
    // The filler after the literal is valid array syntax, so a decoder that ignores the buffer's
    // limit and scans to the end of the array would either throw or swallow the trailing elements.
    String literal = "{1,2}";
    char[] buf = new char[literal.length() + 6];
    literal.getChars(0, literal.length(), buf, 0);
    ",{9,9}".getChars(0, 6, buf, literal.length());

    Object slice = ArrayCodec.INSTANCE.decodeText(
        CharBuffer.wrap(buf, 0, literal.length()), INT4_ARRAY, offline());
    assertArrayEquals(new Integer[]{1, 2}, (Object[]) slice, "view must not read past its limit");
  }
}
