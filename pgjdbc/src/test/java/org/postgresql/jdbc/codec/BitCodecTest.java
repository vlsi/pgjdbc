/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

class BitCodecTest {

  private BitCodec codec;
  private PgType bitType;

  @BeforeEach
  void setUp() {
    codec = BitCodec.INSTANCE;
    bitType = new PgType(
        new ObjectName("pg_catalog", "bit"),
        "bit",
        Oid.BIT,
        'b', 'V', -1, 0, 0, 0
    );
  }

  @Test
  void decodeBinary_valid_roundTrips() throws SQLException {
    // A well-formed value (bit count + packed bits) decodes to its bit string.
    byte[] encoded = codec.encodeBinary("0101", bitType, null);
    PGobject decoded = (PGobject) codec.decodeBinary(encoded, 0, encoded.length, bitType, null);
    assertEquals("0101", decoded.getValue());
  }

  @Test
  void decodeBinary_emptyBitString_roundTrips() throws SQLException {
    // Zero bits: header only, no packed byte.
    byte[] encoded = codec.encodeBinary("", bitType, null);
    PGobject decoded = (PGobject) codec.decodeBinary(encoded, 0, encoded.length, bitType, null);
    assertEquals("", decoded.getValue());
  }

  // ==================== non-binary digits ====================

  // The packed binary form carries one bit per character, so a character that is not '0' or '1' has
  // no representation there: encodeBinary used to write anything but '1' as a zero bit, producing a
  // well-formed value the server accepts. Binding {"abc"} through bit[] therefore stored {000}
  // instead of failing, while the same value sent as text was refused by the server -- the stored
  // result depended on the transfer format. Both directions now refuse with the server's 22P02.

  @Test
  void encodeBinary_nonBinaryDigit_refusesInsteadOfWritingZeroBits() {
    assertNotABitString(() -> codec.encodeBinary("abc", bitType, null));
  }

  @Test
  void encodeText_nonBinaryDigit_refuses() {
    assertNotABitString(() -> codec.encodeText("2", bitType, null));
  }

  @Test
  void encodeBinary_nonBinaryDigitInPGobject_refuses() throws SQLException {
    PGobject value = new PGobject();
    value.setType("bit");
    value.setValue("1x1");
    assertNotABitString(() -> codec.encodeBinary(value, bitType, null));
  }

  @Test
  void encodeBinary_boolean_stillEncodes() throws SQLException {
    // The Boolean branch produces "1"/"0" itself, so the screen must not stand in its way.
    assertEquals("1", ((PGobject) codec.decodeBinary(
        codec.encodeBinary(Boolean.TRUE, bitType, null), 0, 5, bitType, null)).getValue());
  }

  @Test
  void decodeText_nonBinaryDigit_refuses() {
    assertNotABitString(() -> codec.decodeText("abc", bitType, null));
  }

  @Test
  void decodeAsString_nonBinaryDigit_refuses() {
    assertNotABitString(() -> codec.decodeAsString("abc", bitType, null));
  }

  @Test
  void decodeTextAs_nonBinaryDigit_refuses() {
    assertNotABitString(() -> codec.decodeTextAs("abc", bitType, String.class, null));
  }

  @Test
  void decodeText_nonAsciiOneIsNotABinaryDigit() {
    // U+FF11, the fullwidth digit one: a lookalike the character screen must not let through.
    assertNotABitString(() -> codec.decodeText("１", bitType, null));
  }

  @Test
  void emptyBitStringIsNotADigitError() throws SQLException {
    // ''::varbit is a valid zero-length bit string. Length against the column typmod is the server's
    // to check, so the character screen must stay out of it.
    assertEquals("", codec.encodeText("", bitType, null));
  }

  private static void assertNotABitString(org.junit.jupiter.api.function.Executable call) {
    PSQLException e = assertThrows(PSQLException.class, call,
        "a bit string with a non-binary digit should be refused");
    assertEquals(PSQLState.INVALID_TEXT_REPRESENTATION.getState(), e.getSQLState(),
        "SQLState for a non-binary digit");
  }

  // ==================== malformed binary wire (F3b) ====================

  // The binary form is a 4-byte bit count followed by ceil(nbits/8) packed bytes. A count read from
  // corrupt or hostile wire that does not match the bytes present must refuse with a clean
  // PSQLException, rather than drive an OutOfMemoryError on the StringBuilder allocation or an
  // ArrayIndexOutOfBoundsException while unpacking.

  @Test
  void decodeBinary_hugeBitCount_refusesCleanly() {
    // Header claims Integer.MAX_VALUE bits but carries no body: without the guard this both
    // over-allocates the StringBuilder and walks past the buffer.
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, Integer.MAX_VALUE);
    assertRefused(data);
  }

  @Test
  void decodeBinary_bitCountBeyondBody_refusesCleanly() {
    // Header claims 64 bits (8 packed bytes) but only 1 packed byte follows.
    byte[] data = new byte[5];
    ByteConverter.int4(data, 0, 64);
    assertRefused(data);
  }

  @Test
  void decodeBinary_negativeBitCount_refusesCleanly() {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, -1);
    assertRefused(data);
  }

  @Test
  void decodeBinary_shorterThanHeader_refusesCleanly() {
    // Fewer than the 4 header bytes.
    assertRefused(new byte[]{0, 0, 0});
  }

  @Test
  void decodeBinary_trailingGarbage_refusesCleanly() {
    // Header claims 4 bits (1 packed byte) but two packed bytes follow, so the length does not match.
    byte[] data = new byte[6];
    ByteConverter.int4(data, 0, 4);
    assertRefused(data);
  }

  /**
   * Asserts both binary decode entry points refuse {@code data} with a {@link PSQLState#DATA_ERROR}
   * {@link PSQLException} and never leak an unchecked exception.
   */
  private void assertRefused(byte[] data) {
    assertPathRefused("decodeBinary", () -> codec.decodeBinary(data, 0, data.length, (TypeDescriptor) bitType, (CodecContext) null));
    assertPathRefused("decodeAsString", () -> codec.decodeAsString(data, 0, data.length, (TypeDescriptor) bitType, (CodecContext) null));
  }

  private static void assertPathRefused(String path,
      org.junit.jupiter.api.function.Executable decode) {
    PSQLException e = assertThrows(PSQLException.class, decode,
        () -> "bit binary " + path + " should refuse malformed wire");
    assertEquals(PSQLState.DATA_ERROR.getState(), e.getSQLState(),
        () -> "SQLState for bit binary " + path);
  }
}
