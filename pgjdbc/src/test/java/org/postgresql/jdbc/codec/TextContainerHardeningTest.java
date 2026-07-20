/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TypeName;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.OfflineCodecs;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.sql.SQLException;

/**
 * Robustness guards for the text container decoders, the text counterpart of
 * {@link BinaryContainerHardeningTest}.
 *
 * <p>A container's structural framing comes off the wire and was previously trusted past the
 * closing bracket: text that followed it was dropped rather than refused, so {@code (a,1)x}
 * decoded as {@code (a,1)} and a mistyped literal produced a plausible value instead of an error.
 * Every input function ends with this check — {@code record_in} reports "Junk after right
 * parenthesis" — and each of the driver's own container parsers must too.</p>
 *
 * <p>These run offline: the codecs take a {@code null} context, or the connectionless one
 * {@link OfflineCodecs} builds, so the guards are pinned without a server.</p>
 */
class TextContainerHardeningTest {

  private static final PgType INT4 = new PgType(
      TypeName.of("pg_catalog", "int4"), "int4", Oid.INT4, 'b', 'N', -1, 0, 0, 0);

  private static final PgType INT4RANGE = new PgType(
      TypeName.of("pg_catalog", "int4range"), "int4range", 3904,
      'r', 'R', -1, 0, 0, 0).withRangeSubtype(Oid.INT4);

  private static final PgType INT4MULTIRANGE = new PgType(
      TypeName.of("pg_catalog", "int4multirange"), "int4multirange", 4451,
      'm', 'R', -1, 0, 0, 0).withMultirangeRange(3904);

  private static final CodecContext CTX = OfflineCodecs.builder()
      .type(INT4)
      .type(INT4RANGE)
      .type(INT4MULTIRANGE)
      .build();

  private static Object decodeInt4Array(String literal) throws SQLException {
    return MultiDimArrayText.decode(literal, int.class, ',', null, Int4ArrayLeafCodec.INSTANCE);
  }

  // -------------------------- text after the closing bracket --------------------------
  // Every input function ends by refusing whatever follows the container -- record_in reports
  // "Junk after right parenthesis". Without the check the driver read (a,1)x as (a,1), so a
  // mistyped literal handed to PGobject.setValue or PGRange(String) produced a value instead of
  // an error. Trailing whitespace is still fine, as on the server.

  @Test
  void array_refusesTextAfterTheClosingBrace() {
    assertRefused(() -> decodeInt4Array("{1,2}x"));
  }

  @Test
  void arraySplit_refusesTextAfterTheClosingBrace() {
    // PgArray.getResultSet() splits without decoding, so it needs the check of its own.
    assertRefused(() -> ArrayCodec.splitTextArray("{1,2}x", ','));
  }

  @Test
  void composite_refusesTextAfterTheClosingParenthesis() {
    assertRefused(() -> CompositeCodec.parseCompositeText("(a,1)x"));
  }

  @Test
  void range_refusesTextAfterTheClosingBracket() {
    assertRefused(() -> RangeCodec.INSTANCE.decodeText("[1,2)junk", INT4RANGE, null));
    // The inclusive close is a different branch of the same parse.
    assertRefused(() -> RangeCodec.INSTANCE.decodeText("[1,2]extra", INT4RANGE, null));
  }

  @Test
  void multirange_refusesTextAfterTheClosingBrace() {
    // The multirange loop peels several ranges off one cursor, so only the outermost decode may
    // demand the end of the literal.
    assertRefused(() -> MultirangeCodec.INSTANCE.decodeText("{[1,2)}x", INT4MULTIRANGE, CTX));
  }

  @Test
  void trailingWhitespaceIsStillAccepted() throws SQLException {
    assertArrayEquals(new int[]{1, 2}, (int[]) decodeInt4Array("{1,2} "));
    assertArrayEquals(new String[]{"a", "1"}, CompositeCodec.parseCompositeText("(a,1) "));
    assertEquals(2, ArrayCodec.splitTextArray("{1,2} ", ',').elements().size());
  }

  private static void assertRefused(Executable decode) {
    PSQLException e = assertThrows(PSQLException.class, decode,
        "a malformed container literal should refuse cleanly");
    assertEquals(PSQLState.DATA_ERROR.getState(), e.getSQLState(),
        "SQLState for a malformed container literal");
  }
}
