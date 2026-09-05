/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.core.v3.SqlSerializationContext;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.Random;

class PGbyteaTest {

  private static final byte[] HEX_DIGITS_U = new byte[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B',
      'C', 'D', 'E', 'F'};
  private static final byte[] HEX_DIGITS_L = new byte[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b',
      'c', 'd', 'e', 'f'};

  @Test
  void hexDecode_lower() throws SQLException {
    final byte[] data = new byte[1023];
    new Random(7).nextBytes(data);
    final byte[] encoded = hexEncode(data, HEX_DIGITS_L);
    final byte[] decoded = PGbytea.toBytes(encoded);
    assertArrayEquals(data, decoded);
  }

  @Test
  void hexDecode_upper() throws SQLException {
    final byte[] data = new byte[9513];
    new Random(-8).nextBytes(data);
    final byte[] encoded = hexEncode(data, HEX_DIGITS_U);
    final byte[] decoded = PGbytea.toBytes(encoded);
    assertArrayEquals(data, decoded);
  }

  @Test
  void toPGLiteral_byteArray() throws IOException {
    assertEquals("'\\x00010203'::bytea",
        PGbytea.toPGLiteral(new byte[]{0, 1, 2, 3}, SqlSerializationContext.of(true, true)));
  }

  @Test
  void toPGLiteral_hexString() throws IOException {
    assertEquals("'\\x00010203'::bytea",
        PGbytea.toPGLiteral("\\x00010203", SqlSerializationContext.of(true, true)));
  }

  @Test
  void toPGLiteral_hexString_upperCaseDigits() throws IOException {
    assertEquals("'\\xCAFEBABE'::bytea",
        PGbytea.toPGLiteral("\\xCAFEBABE", SqlSerializationContext.of(true, true)));
  }

  @Test
  void toPGLiteral_hexString_whitespaceIsAllowed() throws IOException {
    assertEquals("'\\x00 01\t02'::bytea",
        PGbytea.toPGLiteral("\\x00 01\t02", SqlSerializationContext.of(true, true)));
  }

  @Test
  void toPGLiteral_hexString_empty() throws IOException {
    assertEquals("'\\x'::bytea",
        PGbytea.toPGLiteral("\\x", SqlSerializationContext.of(true, true)));
  }

  @Test
  void toPGLiteral_byteArray_nonStandardConformingStrings() throws IOException {
    // With standard_conforming_strings off a backslash in a plain literal starts an escape
    // sequence, so the literal has to be an escape string constant with the backslash doubled
    assertEquals("E'\\\\x00010203'::bytea",
        PGbytea.toPGLiteral(new byte[]{0, 1, 2, 3}, SqlSerializationContext.of(false, true)));
  }

  @Test
  void toPGLiteral_hexString_nonStandardConformingStrings() throws IOException {
    assertEquals("E'\\\\x00010203'::bytea",
        PGbytea.toPGLiteral("\\x00010203", SqlSerializationContext.of(false, true)));
  }

  @Test
  void toPGLiteral_hexString_empty_nonStandardConformingStrings() throws IOException {
    assertEquals("E'\\\\x'::bytea",
        PGbytea.toPGLiteral("\\x", SqlSerializationContext.of(false, true)));
  }

  @Test
  void toPGLiteral_streamWrapper_nonStandardConformingStrings() throws IOException {
    assertEquals("E'\\\\x00010203'::bytea",
        PGbytea.toPGLiteral(new StreamWrapper(new byte[]{0, 1, 2, 3}, 0, 4),
            SqlSerializationContext.of(false, true)));
  }

  @Test
  void toPGLiteral_byteStreamWriter_nonStandardConformingStrings() throws IOException {
    assertEquals("E'\\\\x00010203'::bytea",
        PGbytea.toPGLiteral(new ByteBufferByteStreamWriter(ByteBuffer.wrap(new byte[]{0, 1, 2, 3})),
            SqlSerializationContext.of(false, true)));
  }

  @Test
  void toPGLiteral_hexString_rejectsNonHexCharacter() {
    assertThrows(IllegalArgumentException.class,
        () -> PGbytea.toPGLiteral("\\x00zz", SqlSerializationContext.of(true, true)));
  }

  @Test
  void toPGLiteral_hexString_rejectsOddNumberOfDigits() {
    // PostgreSQL rejects an odd number of hex digits, so validate it here too
    assertThrows(IllegalArgumentException.class,
        () -> PGbytea.toPGLiteral("\\xcaf", SqlSerializationContext.of(true, true)));
  }

  @Test
  void toPGLiteral_hexString_rejectsFormFeed() {
    // PostgreSQL ignores space, tab, newline and carriage return, but not form feed
    assertThrows(IllegalArgumentException.class,
        () -> PGbytea.toPGLiteral("\\xca\ffe", SqlSerializationContext.of(true, true)));
  }

  @Test
  void toPGLiteral_hexString_rejectsInjectionAttempt() {
    // A quote must not slip into the literal unescaped
    assertThrows(IllegalArgumentException.class,
        () -> PGbytea.toPGLiteral("\\x00'::bytea); drop table t; --",
            SqlSerializationContext.of(true, true)));
  }

  @Test
  void toPGLiteral_string_rejectsMissingHexPrefix() {
    assertThrows(IllegalArgumentException.class,
        () -> PGbytea.toPGLiteral("00010203", SqlSerializationContext.of(true, true)));
  }

  private static byte[] hexEncode(byte[] data, byte[] hexDigits) {

    // the string created will have 2 characters for each byte.
    // and 2 lead characters to indicate hex encoding
    final byte[] encoded = new byte[2 + (data.length << 1)];
    encoded[0] = '\\';
    encoded[1] = 'x';
    for (int i = 0; i < data.length; i++) {
      final int idx = (i << 1) + 2;
      final byte b = data[i];
      encoded[idx] = hexDigits[(b & 0xF0) >>> 4];
      encoded[idx + 1] = hexDigits[b & 0x0F];
    }
    return encoded;
  }
}
