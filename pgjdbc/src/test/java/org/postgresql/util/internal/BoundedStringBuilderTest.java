/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * Fails when {@link BoundedStringBuilder} keeps more characters than its limit allows.
 *
 * <p>The driver builds protocol traces out of query text and bound values, so the limit is what
 * stops a large statement from exhausting the heap through logging. A builder must never hold more
 * than the limit, and it must tell the reader that characters went missing.</p>
 */
class BoundedStringBuilderTest {
  private static final String MARKER = "...(truncated)";

  private static final String FIELD_MARKER = "...";

  /**
   * U+1F600, which UTF-16 stores as a surrogate pair, so a cut between its two characters leaves
   * text that is not valid UTF-16.
   */
  private static final String EMOJI = new String(Character.toChars(0x1F600));

  @Test
  void unlimitedKeepsEverything() {
    BoundedStringBuilder sb = new BoundedStringBuilder(BoundedStringBuilder.UNLIMITED);
    sb.append("abc").append('d').append(42);
    assertTrue(sb.isUnbounded(), "isUnbounded");
    assertFalse(sb.isFull(), "isFull");
    assertFalse(sb.isTruncated(), "isTruncated");
    assertEquals("abcd42", sb.toString());
  }

  @Test
  void negativeLimitMeansUnlimited() {
    BoundedStringBuilder sb = new BoundedStringBuilder(-7);
    sb.append("abc");
    assertTrue(sb.isUnbounded(), "isUnbounded");
    assertEquals("abc", sb.toString());
  }

  @Test
  void fittingValueIsKeptVerbatim() {
    BoundedStringBuilder sb = new BoundedStringBuilder(64);
    sb.append("select 1");
    assertFalse(sb.isTruncated(), "isTruncated");
    assertEquals("select 1", sb.toString());
    assertEquals(64 - "select 1".length(), sb.remaining(), "remaining");
  }

  @Test
  void charSequenceIsCutAtTheLimit() {
    BoundedStringBuilder sb = new BoundedStringBuilder(30);
    sb.append(repeat('x', 1000));
    assertTrue(sb.isTruncated(), "isTruncated");
    assertTrue(sb.isFull(), "isFull");
    assertEquals(0, sb.remaining(), "remaining");
    String result = sb.toString();
    assertEquals(30, result.length(), "result length");
    assertTrue(result.endsWith(MARKER), () -> "result should end with the marker: " + result);
    assertEquals(repeat('x', 30 - MARKER.length()) + MARKER, result);
  }

  @Test
  void appendsAfterTheLimitAreDropped() {
    BoundedStringBuilder sb = new BoundedStringBuilder(20);
    sb.append(repeat('x', 20));
    assertFalse(sb.isTruncated(), "an exactly fitting value is not a truncated one");
    sb.append('y');
    sb.append("z");
    sb.append(7);
    assertTrue(sb.isTruncated(), "isTruncated");
    assertEquals(repeat('x', 20 - MARKER.length()) + MARKER, sb.toString());
  }

  @Test
  void aLimitNarrowerThanTheMarkerCutsTheMarker() {
    BoundedStringBuilder narrower = new BoundedStringBuilder(3);
    narrower.append("abcdef");
    assertEquals("...", narrower.toString(), "the marker is cut to the limit, not kept whole");

    BoundedStringBuilder exact = new BoundedStringBuilder(MARKER.length());
    exact.append(repeat('a', MARKER.length() + 1));
    assertEquals(MARKER, exact.toString());

    BoundedStringBuilder wider = new BoundedStringBuilder(MARKER.length() + 1);
    wider.append(repeat('a', MARKER.length() + 2));
    assertEquals("a" + MARKER, wider.toString());
  }

  @Test
  void aSurrogatePairIsNotCutInHalf() {
    // The cut lands 6 characters in, which is the high surrogate of the first pair.
    BoundedStringBuilder sb = new BoundedStringBuilder(MARKER.length() + 6);
    sb.append(repeat('a', 5) + repeat(EMOJI, 8));

    String result = sb.toString();
    assertEquals(repeat('a', 5) + MARKER, result, "the split high surrogate is dropped");
    assertNoLoneSurrogate(result);
  }

  @Test
  void aCutBetweenPairsKeepsEveryCharacter() {
    // The same value one character further along puts the cut between two pairs.
    BoundedStringBuilder sb = new BoundedStringBuilder(MARKER.length() + 6);
    sb.append(repeat('a', 6) + repeat(EMOJI, 8));

    assertEquals(repeat('a', 6) + MARKER, sb.toString(), "nothing is dropped when the cut is clean");
  }

  @Test
  void aFieldIsCutAtItsOwnBudget() {
    BoundedStringBuilder sb = new BoundedStringBuilder(BoundedStringBuilder.UNLIMITED);
    sb.append("$1=");
    sb.beginField(5).append(repeat('a', 100)).endField();
    sb.append(",$2=").append("42");

    assertEquals("$1=aaaaa...,$2=42", sb.toString());
    assertFalse(sb.isTruncated(), "a cut field does not make the message a truncated one");
  }

  @Test
  void aFieldThatFitsIsNotMarked() {
    BoundedStringBuilder sb = new BoundedStringBuilder(BoundedStringBuilder.UNLIMITED);
    sb.beginField(5).append("abcde").endField();

    assertEquals("abcde", sb.toString(), "a field filled exactly to its budget lost nothing");
  }

  @Test
  void theLimitStillWinsInsideAField() {
    BoundedStringBuilder sb = new BoundedStringBuilder(20);
    sb.beginField(1000).append(repeat('a', 100)).endField();

    assertTrue(sb.isTruncated(), "the message ran out, not the field");
    assertEquals(repeat('a', 20 - MARKER.length()) + MARKER, sb.toString());
  }

  @Test
  void anOpenFieldMakesTheBuilderBounded() {
    BoundedStringBuilder sb = new BoundedStringBuilder(BoundedStringBuilder.UNLIMITED);
    assertTrue(sb.isUnbounded(), "no limit and no field");

    sb.beginField(5);
    assertFalse(sb.isUnbounded(), "a field bounds the builder even without a limit");

    sb.endField();
    assertTrue(sb.isUnbounded(), "the field is closed again");
  }

  @Test
  void reopeningAFieldClosesThePreviousOne() {
    BoundedStringBuilder sb = new BoundedStringBuilder(BoundedStringBuilder.UNLIMITED);
    sb.append("$1=");
    sb.beginField(3).append("aaaaaaaa");
    sb.beginField(100).append("bb");
    sb.endField();

    assertEquals("$1=aaa...bb", sb.toString(),
        "the abandoned field keeps its own marker instead of lending it to the next one");
  }

  @Test
  void aFieldCutDoesNotSplitASurrogatePair() {
    BoundedStringBuilder sb = new BoundedStringBuilder(BoundedStringBuilder.UNLIMITED);
    sb.append("$1=");
    // The budget ends between the two characters of the first pair.
    sb.beginField(3).append("ab" + repeat(EMOJI, 4)).endField();
    sb.append(",$2=42");

    String result = sb.toString();
    assertEquals("$1=ab...,$2=42", result, "the split high surrogate is dropped");
    assertNoLoneSurrogate(result);
  }

  @Test
  void aDroppedCharacterKeepsTheSinkClosed() {
    BoundedStringBuilder sb = new BoundedStringBuilder(BoundedStringBuilder.UNLIMITED);
    // The budget ends inside the pair, so the high surrogate leaves the buffer again. The slot it
    // frees must not let the next append overtake the character that was dropped.
    sb.beginField(5).append("abcd" + EMOJI).append("ZZZZ").endField();

    assertEquals("abcd...", sb.toString());
  }

  @Test
  void roomNeverGrowsWhileAFieldIsOpen() {
    int splits = 0;
    for (int limit : new int[]{BoundedStringBuilder.UNLIMITED, 20, 40, 80}) {
      // A contiguous range, so some budget has to end between the two characters of a pair.
      for (int budget = 1; budget <= 8; budget++) {
        BoundedStringBuilder sb = new BoundedStringBuilder(limit);
        sb.beginField(budget);
        int previous = sb.remaining();
        for (String piece : new String[]{"a", EMOJI, "bb", EMOJI, "ccc"}) {
          sb.append(piece);
          int now = sb.remaining();
          int seen = previous;
          int cut = budget;
          assertTrue(now <= seen,
              () -> "room grew from " + seen + " to " + now + " at limit " + limit
                  + " and budget " + cut);
          previous = now;
        }
        sb.endField();
        if (limit == BoundedStringBuilder.UNLIMITED && keptLength(sb) < budget) {
          // The field kept fewer characters than it was allowed, which only happens when a split
          // surrogate left the buffer again.
          splits++;
        }
      }
    }
    assertTrue(splits > 0, "no budget landed inside a surrogate pair, so nothing was removed");
  }

  @Test
  void messageRoomNeverGrowsAcrossFields() {
    BoundedStringBuilder sb = new BoundedStringBuilder(200);
    int previous = sb.remaining();
    for (int i = 1; i <= 10; i++) {
      sb.append(",$").append(i).append("=<");
      // A budget that ends inside the pair, so every field leaves a character behind.
      sb.beginField(2).append("a" + EMOJI + EMOJI).endField();
      sb.append(">");

      int now = sb.remaining();
      int seen = previous;
      assertTrue(now <= seen, () -> "message room grew from " + seen + " to " + now);
      previous = now;
    }
    assertTrue(sb.toString().contains("=<a...>"), () -> "expected every field to be cut just after "
        + "its first character: " + sb);
    // Nine parameters spell ",$i=<a...>" and the tenth ",$10=<a...>", which is 101 characters, and
    // each of the ten cuts charges the surrogate it removed, so 111 of the 200 are spent.
    assertEquals(200 - 111, sb.remaining(), "room left after ten cut fields");
  }

  @Test
  void aZeroBudgetOpensNoField() {
    BoundedStringBuilder sb = new BoundedStringBuilder(BoundedStringBuilder.UNLIMITED);
    sb.beginField(0).append(repeat('a', 100)).endField();

    assertEquals(repeat('a', 100), sb.toString());
  }

  @Test
  void ensureRoomForDoesNotChangeWhatIsKept() {
    BoundedStringBuilder bounded = new BoundedStringBuilder(20);
    bounded.ensureRoomFor(1_000_000);
    bounded.append(repeat('x', 100));
    assertEquals(repeat('x', 20 - MARKER.length()) + MARKER, bounded.toString());

    BoundedStringBuilder unlimited = new BoundedStringBuilder(BoundedStringBuilder.UNLIMITED);
    unlimited.ensureRoomFor(-1);
    unlimited.append("abc");
    assertEquals("abc", unlimited.toString(), "a hint that overflowed is ignored");
  }

  @Test
  void intIsCutAtTheLimit() {
    BoundedStringBuilder fits = new BoundedStringBuilder(20);
    fits.append(1234567);
    assertFalse(fits.isTruncated(), "isTruncated");
    assertEquals("1234567", fits.toString());

    BoundedStringBuilder overflows = new BoundedStringBuilder(20);
    overflows.append(repeat('x', 15)).append(1234567);
    assertTrue(overflows.isTruncated(), "isTruncated");
    assertEquals(repeat('x', 20 - MARKER.length()) + MARKER, overflows.toString());
  }

  @Test
  void nullCharSequenceIsSpelledOut() {
    BoundedStringBuilder sb = new BoundedStringBuilder(64);
    sb.append((CharSequence) null);
    assertEquals("null", sb.toString());
  }

  @Test
  void toStringDoesNotConsumeTheBuilder() {
    BoundedStringBuilder sb = new BoundedStringBuilder(20);
    sb.append(repeat('x', 100));
    assertEquals(sb.toString(), sb.toString(), "toString is repeatable");
  }

  /**
   * Fails when {@code value} is not well-formed UTF-16. An unpaired surrogate does not survive a
   * round trip through UTF-8, which is what a log handler that encodes the message would do to it.
   */
  private static void assertNoLoneSurrogate(String value) {
    assertEquals(value, new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
        "value is not well-formed UTF-16");
  }

  private static int keptLength(BoundedStringBuilder sb) {
    String rendered = sb.toString();
    return rendered.endsWith(FIELD_MARKER)
        ? rendered.length() - FIELD_MARKER.length() : rendered.length();
  }

  private static String repeat(char c, int count) {
    StringBuilder sb = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
      sb.append(c);
    }
    return sb.toString();
  }

  private static String repeat(String value, int count) {
    StringBuilder sb = new StringBuilder(value.length() * count);
    for (int i = 0; i < count; i++) {
      sb.append(value);
    }
    return sb.toString();
  }
}
