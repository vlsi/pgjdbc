/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeName;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;

import org.junit.jupiter.api.Test;

import java.nio.CharBuffer;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * Verifies that {@code decodeText} reads exactly its {@link CharSequence}, whatever backs it. Each
 * value decodes the same as a standalone {@code String}, as a {@code CharBuffer} over a whole
 * {@code char[]}, and as a {@code CharBuffer} over a slice embedded in a larger buffer with noise
 * on both sides. Container codecs (ranges, composites and the generic array leaf) hand each element
 * down as a borrowed view to skip a per-value {@code String}, so the view result must match the
 * {@code String} result exactly.
 *
 * <p>End-to-end coverage through {@code RangeCodec} / {@code CompositeCodec} with a live element
 * codec lives in the integration suites; resolving the subtype codec needs a real
 * {@code CodecContext}.</p>
 */
class TextCodecCharSequenceTest {

  private static final PgType ANY = new PgType(
      TypeName.of("pg_catalog", "int4"), "integer", Oid.INT4, 'b', 'N', -1, 0, 0, 0);
  private static final CodecContext CTX = TestCodecContext.create();

  /** Embeds {@code value} at offset 5 of a noise-filled char[] with trailing padding. */
  private static char[] embed(String value) {
    char[] buf = new char[5 + value.length() + 3];
    Arrays.fill(buf, '#'); // non-digit noise to catch offset/length bugs
    value.getChars(0, value.length(), buf, 5);
    return buf;
  }

  /** A view over {@code value} embedded at offset 5, with noise before and after it. */
  private static CharBuffer embedded(String value) {
    return CharBuffer.wrap(embed(value), 5, value.length());
  }

  /** Decodes {@code text} as a String, as a whole-buffer view and as an embedded view. */
  private static void assertViewMatchesString(TextCodec codec, String text) throws SQLException {
    Object viaString = codec.decodeText(text, ANY, CTX);
    char[] whole = text.toCharArray();
    assertEquals(viaString, codec.decodeText(CharBuffer.wrap(whole), ANY, CTX), "whole buffer");
    assertEquals(viaString, codec.decodeText(embedded(text), ANY, CTX), "embedded slice");
  }

  @Test
  void int2_viewMatchesString() throws SQLException {
    for (String v : new String[]{"0", "-1", "1234", "32767", "-32768"}) {
      assertViewMatchesString(Int2Codec.INSTANCE, v);
    }
    assertEquals(1234, Int2Codec.INSTANCE.decodeText(embedded("1234"), ANY, CTX));
  }

  @Test
  void int4_viewMatchesString() throws SQLException {
    for (String v : new String[]{"0", "-1", "123456", "2147483647", "-2147483648"}) {
      assertViewMatchesString(Int4Codec.INSTANCE, v);
    }
    assertEquals(-123456, Int4Codec.INSTANCE.decodeText(embedded("-123456"), ANY, CTX));
  }

  @Test
  void int8_viewMatchesString() throws SQLException {
    for (String v : new String[]{"0", "-1", "9000000000",
        "9223372036854775807", "-9223372036854775808"}) {
      assertViewMatchesString(Int8Codec.INSTANCE, v);
    }
    assertEquals(9_000_000_000L, Int8Codec.INSTANCE.decodeText(embedded("9000000000"), ANY, CTX));
  }

  @Test
  void oid_viewMatchesString() throws SQLException {
    // 4000000000 exceeds Integer.MAX_VALUE; oid text decodes as the full signed long.
    for (String v : new String[]{"0", "16384", "4000000000"}) {
      assertViewMatchesString(OidCodec.INSTANCE, v);
    }
    assertEquals(4_000_000_000L, OidCodec.INSTANCE.decodeText(embedded("4000000000"), ANY, CTX));
  }

  @Test
  void leadingPlus_matchesStringParser() throws SQLException {
    // A leading '+' is not part of the fast path; both forms fall back to the
    // String integer parser and yield the same value.
    assertEquals(Int4Codec.INSTANCE.decodeText("+5", ANY, CTX),
        Int4Codec.INSTANCE.decodeText(embedded("+5"), ANY, CTX));
  }

  @Test
  void outOfRange_throwsLikeStringForm() {
    // An int2 overflow must fail through a view exactly as through a String.
    assertThrows(SQLException.class, () -> Int2Codec.INSTANCE.decodeText("99999", ANY, CTX));
    assertThrows(SQLException.class,
        () -> Int2Codec.INSTANCE.decodeText(embedded("99999"), ANY, CTX));
  }

  @Test
  void invalidDigits_throwsLikeStringForm() {
    assertThrows(SQLException.class, () -> Int4Codec.INSTANCE.decodeText(embedded("12x4"), ANY, CTX));
  }

  @Test
  void materializingCodecs_readOnlyTheView() throws SQLException {
    // float8 and bool parse through a String they build themselves; they must still read exactly
    // the view's window and not the noise around it.
    assertEquals(Float8Codec.INSTANCE.decodeText("2.5", ANY, CTX),
        Float8Codec.INSTANCE.decodeText(embedded("2.5"), ANY, CTX));
    assertEquals(BoolCodec.INSTANCE.decodeText("t", ANY, CTX),
        BoolCodec.INSTANCE.decodeText(embedded("t"), ANY, CTX));
  }
}
