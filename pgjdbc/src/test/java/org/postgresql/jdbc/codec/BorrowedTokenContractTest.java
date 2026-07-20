/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeName;
import org.postgresql.jdbc.OfflineCodecs;
import org.postgresql.jdbc.PgField;
import org.postgresql.jdbc.PgType;
import org.postgresql.test.consumer.borrowed.BorrowedTokenProbeCodec;
import org.postgresql.util.PGRange;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Struct;
import java.util.Arrays;

/**
 * Pins the {@link TextCodec#decodeText} buffer-ownership contract from the child codec's side.
 *
 * <p>Containers hand each element down as a borrowed view over the parent literal, and the same
 * view instance is reused from one token to the next. Two things have to hold for that to be safe.
 * The view a child sees must be exactly its own token — quotes stripped, escapes resolved, nothing
 * of the neighbours. And a child that materializes the text with {@code toString()}, as the
 * contract prescribes, must end up with a value that survives the cursor moving on.</p>
 *
 * <p>{@link BorrowedTokenProbeCodec} returns each token it sees wrapped in angle brackets, so the
 * decoded container spells out what reached every element. Both properties then follow from the
 * decoded values: a container that handed down the wrong window would show the wrong characters,
 * and one whose child had retained the view instead of copying would show a later token's
 * characters in an earlier slot.</p>
 *
 * <p>The assertions deliberately never compare view identity — the view is reused on purpose, so
 * two tokens sharing an instance is the design, not a defect.</p>
 */
class BorrowedTokenContractTest {

  private static final int PROBE_OID = 91_001;
  private static final int PROBE_ARRAY_OID = 91_002;
  private static final int PROBE_RANGE_OID = 91_003;
  private static final int COMPOSITE_OID = 91_004;

  private static final PgType PROBE = new PgType(
      TypeName.of("public", BorrowedTokenProbeCodec.TYPE_NAME),
      "public." + BorrowedTokenProbeCodec.TYPE_NAME, PROBE_OID, 'b', 'S', -1, 0, PROBE_ARRAY_OID, 0);

  private static final PgType PROBE_ARRAY = new PgType(
      TypeName.of("public", "_" + BorrowedTokenProbeCodec.TYPE_NAME),
      "public." + BorrowedTokenProbeCodec.TYPE_NAME + "[]", PROBE_ARRAY_OID, 'b', 'A', -1,
      PROBE_OID, 0, 0);

  private static final PgType PROBE_RANGE = new PgType(
      TypeName.of("public", "probe_range"), "public.probe_range", PROBE_RANGE_OID, 'r', 'R', -1,
      0, 0, 0).withRangeSubtype(PROBE_OID);

  private static final int NESTED_OID = 91_005;

  private static final PgType NESTED = new PgType(
      TypeName.of("public", "nested"), "public.nested", NESTED_OID, 'c', 'C', -1, 0, 0, 0,
      Arrays.asList(new PgField("arr", PROBE_ARRAY_OID, 1, -1),
          new PgField("rng", PROBE_RANGE_OID, 2, -1)));

  private static final PgType PAIR = new PgType(
      TypeName.of("public", "pair"), "public.pair", COMPOSITE_OID, 'c', 'C', -1, 0, 0, 0,
      Arrays.asList(new PgField("a", PROBE_OID, 1, -1), new PgField("b", PROBE_OID, 2, -1)));

  private static CodecContext ctx() {
    return OfflineCodecs.builder()
        .type(PROBE).type(PROBE_ARRAY).type(PROBE_RANGE).type(PAIR).type(NESTED)
        .build();
  }

  @Test
  void arrayHandsEachElementDownAsItsOwnToken() throws SQLException {
    Object decoded = ArrayCodec.INSTANCE.decodeText("{one,two,three}", PROBE_ARRAY, ctx());
    assertArrayEquals(new Object[]{"<one>", "<two>", "<three>"}, (Object[]) decoded);
  }

  @Test
  void arrayUnescapesQuotedElementsBeforeHandingThemDown() throws SQLException {
    // {"a,b","c""d",plain} — the child must see the logical values, not the array syntax.
    Object decoded =
        ArrayCodec.INSTANCE.decodeText("{\"a,b\",\"c\"\"d\",plain}", PROBE_ARRAY, ctx());
    assertArrayEquals(new Object[]{"<a,b>", "<c\"d>", "<plain>"}, (Object[]) decoded);
  }

  @Test
  void compositeHandsEachFieldDownAsItsOwnToken() throws SQLException {
    Struct decoded = (Struct) CompositeCodec.INSTANCE.decodeText("(\"x,y\",z)", PAIR, ctx());
    assertArrayEquals(new Object[]{"<x,y>", "<z>"}, decoded.getAttributes());
  }

  @Test
  void rangeHandsEachBoundDownAsItsOwnToken() throws SQLException {
    PGRange<?> decoded =
        (PGRange<?>) RangeCodec.INSTANCE.decodeText("[\"lo,w\",high)", PROBE_RANGE, ctx());
    assertEquals("<lo,w>", decoded.getLower());
    assertEquals("<high>", decoded.getUpper());
  }

  @Test
  void materializedTextOutlivesTheCursorMovingOn() throws SQLException {
    // Source-buffer and scratch-buffer tokens of different lengths, in both directions. A child
    // that kept the borrowed view would report a later token's characters here, and a cursor that
    // failed to repoint the view after growing its scratch buffer would report an earlier one's.
    Object decoded = ArrayCodec.INSTANCE.decodeText(
        "{\"a\"\"b\",short,\"muuuuuch longer\",z}", PROBE_ARRAY, ctx());
    assertArrayEquals(new Object[]{"<a\"b>", "<short>", "<muuuuuch longer>", "<z>"},
        (Object[]) decoded);
  }

  @Test
  void nestedContainersPeelOneLevelPerCursor() throws SQLException {
    // A composite whose fields are themselves containers: each field arrives as a borrowed token,
    // and the child container opens its own cursor over that token rather than over a copy. The
    // leaf values pin that the offsets survive both levels, and the array element that needed
    // un-escaping pins that a token peeled into scratch is still a valid literal for the child.
    // literal: ("{one,""t,wo""}","[lo,hi)")
    Struct decoded = (Struct) CompositeCodec.INSTANCE.decodeText(
        "(\"{one,\"\"t,wo\"\"}\",\"[lo,hi)\")", NESTED, ctx());
    Object[] attributes = decoded.getAttributes();
    assertArrayEquals(new Object[]{"<one>", "<t,wo>"}, (Object[]) attributes[0]);
    PGRange<?> range = (PGRange<?>) attributes[1];
    assertEquals("<lo>", range.getLower());
    assertEquals("<hi>", range.getUpper());
  }
}
