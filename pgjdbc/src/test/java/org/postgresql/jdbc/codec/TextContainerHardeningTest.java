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
 * <p>Both guards here exist because a container's structural framing comes off the wire and was
 * previously trusted. Text after the closing bracket was dropped rather than refused, so a
 * mistyped literal decoded to a plausible value; and the dimension count, read as a run of leading
 * braces, sized both the dimension-length array and the measuring pass unchecked, which drove a
 * {@link StackOverflowError} out of the driver — an {@link Error}, so it escaped every
 * {@code SQLException} handler on the path — and leaked an {@link IllegalArgumentException} from
 * {@code Array.newInstance} for shallower-but-still-oversized literals. Both must refuse as a
 * {@link PSQLException}, and the dimension bound must be the {@code MAXDIM} the binary header is
 * already held to, so a literal is not accepted in one wire format and rejected in the other.</p>
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

  // -------------------------- dimensions above MAXDIM --------------------------

  /** Builds {@code {{{…1…}}}} with {@code dimensions} levels of braces around a single element. */
  private static String nested(int dimensions) {
    StringBuilder sb = new StringBuilder(2 * dimensions + 1);
    for (int i = 0; i < dimensions; i++) {
      sb.append('{');
    }
    sb.append('1');
    for (int i = 0; i < dimensions; i++) {
      sb.append('}');
    }
    return sb.toString();
  }

  @Test
  void atMaxdim_decodes() throws SQLException {
    // Six dimensions is the server's MAXDIM and must still round-trip.
    Object decoded = decodeInt4Array(nested(6));
    assertArrayEquals(new int[]{1}, (int[]) ((Object[]) ((Object[]) ((Object[]) ((Object[])
        ((Object[]) decoded)[0])[0])[0])[0])[0]);
  }

  @Test
  void oneAboveMaxdim_refusesCleanly() {
    assertRefused(() -> decodeInt4Array(nested(7)));
  }

  @Test
  void deeplyNested_refusesCleanlyWithoutStackOverflow() {
    // Regression pin: this used to recurse once per brace and die with a StackOverflowError.
    assertRefused(() -> decodeInt4Array(nested(100_000)));
  }

  @Test
  void moderatelyNested_refusesCleanlyRatherThanLeakingFromArrayNewInstance() {
    // Above MAXDIM but below the JVM's own 255-dimension ceiling, so the old code reached
    // Array.newInstance and surfaced its unchecked IllegalArgumentException.
    assertRefused(() -> decodeInt4Array(nested(200)));
  }

  @Test
  void dimensionPrefixedLiteralAboveMaxdim_refusesCleanly() {
    // The [l:u]= prefix is skipped before the braces are counted, so the guard still applies.
    assertRefused(() -> decodeInt4Array("[1:1][1:1][1:1][1:1][1:1][1:1][1:1]=" + nested(7)));
  }

  private static void assertRefused(Executable decode) {
    PSQLException e = assertThrows(PSQLException.class, decode,
        "a malformed container literal should refuse cleanly");
    assertEquals(PSQLState.DATA_ERROR.getState(), e.getSQLState(),
        "SQLState for a malformed container literal");
  }
}
