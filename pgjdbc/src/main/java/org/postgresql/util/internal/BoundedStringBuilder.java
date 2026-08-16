/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Collects characters up to a fixed limit and discards everything past it.
 *
 * <p>A protocol trace embeds query text and parameter values, so a message built with a plain
 * {@link StringBuilder} grows with the data the application sends, and logging becomes a source of
 * {@code OutOfMemoryError} (issue #995). This class bounds that cost: the buffer never holds more
 * than the limit given to the constructor, and {@link #toString()} ends a message that lost
 * characters with a marker.</p>
 *
 * <p>Discarding is silent, so a producer that appends without checking anything still stays within
 * the limit. A producer that can skip work cheaply should ask {@link #remaining()} or
 * {@link #isFull()} first: appending a discarded 200 MB value costs no memory, but it still costs
 * the time to walk the value.</p>
 *
 * <p>What the builder keeps is always a prefix of what it was offered, because the room never
 * grows while a budget is in force: neither the message room nor an open field's room recovers
 * once a character has been dropped, so nothing appended later can overtake it. Closing a field
 * does restore the message room, which is what lets the next field render at all.</p>
 *
 * <p>Instances are not thread-safe.</p>
 */
public final class BoundedStringBuilder implements Appendable {
  /**
   * Limit that lets the builder grow as far as the heap allows.
   */
  public static final int UNLIMITED = 0;

  /**
   * Marks a message that lost characters. {@link #toString()} keeps the result within the limit by
   * dropping as many trailing characters as the marker takes, and by shortening the marker itself
   * when the limit is narrower than it is.
   */
  private static final String TRUNCATION_MARKER = "...(truncated)";

  /**
   * Marks a field that lost characters. A field is cut in the middle of a message, so it needs a
   * marker of its own rather than the one {@link #toString()} puts at the end.
   */
  private static final String FIELD_TRUNCATION_MARKER = "...";

  /**
   * Characters in the widest decimal representation of an {@code int}, which is
   * {@code -2147483648}.
   */
  private static final int MAX_INT_LENGTH = 11;

  /**
   * Value of {@link #fieldEnd} while no field is open.
   */
  private static final int NO_FIELD = -1;

  private final StringBuilder buf = new StringBuilder();
  private final int limit;
  private boolean truncated;
  private int fieldEnd = NO_FIELD;
  private boolean fieldTruncated;
  private int removed;

  /**
   * Creates a builder that keeps at most {@code limit} characters.
   *
   * @param limit number of characters to keep; {@link #UNLIMITED} and any negative value mean no
   *     limit
   */
  public BoundedStringBuilder(int limit) {
    this.limit = limit;
  }

  /**
   * Returns true when nothing bounds the builder right now, so a producer may hand it a value of
   * any size.
   *
   * <p>An open field bounds the builder even when the limit does not, which is why a producer that
   * decides whether to render a value has to ask this rather than whether a limit was configured.
   * </p>
   */
  public boolean isUnbounded() {
    return remaining() == Integer.MAX_VALUE;
  }

  /**
   * Returns true when no limit was configured, which says nothing about an open field.
   */
  private boolean isUnlimited() {
    return limit <= UNLIMITED;
  }

  /**
   * Returns the number of characters the builder still accepts, which is the smaller of what the
   * limit and the open field allow, or {@link Integer#MAX_VALUE} when neither bounds it.
   */
  public @NonNegative int remaining() {
    int room = isUnlimited() ? Integer.MAX_VALUE : Math.max(0, limit - used());
    if (fieldEnd == NO_FIELD) {
      return room;
    }
    return Math.min(room, Math.max(0, fieldEnd - used()));
  }

  /**
   * Returns the characters the builder has spent, which is what it holds plus what
   * {@link #dropSplitSurrogate()} took back out of it.
   *
   * <p>Counting a removed character as spent is what keeps a budget from re-opening behind a
   * character it already dropped: the freed slot would otherwise let the next append overtake the
   * dropped one, and the kept text would no longer be a prefix of the text that was offered.</p>
   */
  private int used() {
    return buf.length() + removed;
  }

  /**
   * Returns true when further appends are discarded.
   */
  public boolean isFull() {
    return remaining() == 0;
  }

  /**
   * Returns true when the message lost characters. A field cut short by {@link #beginField} does
   * not count, because it carries its own marker.
   */
  public boolean isTruncated() {
    return truncated;
  }

  /**
   * Caps the next {@code chars} characters so that one long field cannot spend the whole message
   * on itself, and returns this builder.
   *
   * <p>The field runs until {@link #endField()}, which marks it when it lost characters. Fields do
   * not nest: opening one closes the budget of any field still open. A {@code chars} of zero or
   * less opens no field at all, which is how the caller turns the cap off.</p>
   */
  public BoundedStringBuilder beginField(int chars) {
    endField();
    fieldEnd = chars <= 0 ? NO_FIELD : (int) Math.min(Integer.MAX_VALUE, (long) used() + chars);
    return this;
  }

  /**
   * Ends the field {@link #beginField} opened, appending {@value #FIELD_TRUNCATION_MARKER} when the
   * field lost characters, and returns this builder.
   */
  public BoundedStringBuilder endField() {
    fieldEnd = NO_FIELD;
    if (fieldTruncated) {
      fieldTruncated = false;
      append(FIELD_TRUNCATION_MARKER);
    }
    return this;
  }

  /**
   * Records that characters were dropped, against the field when the field is what ran out and
   * against the message otherwise.
   */
  private void recordDrop() {
    if (isUnlimited() || used() < limit) {
      fieldTruncated = true;
    } else {
      truncated = true;
    }
  }

  /**
   * Removes a trailing high surrogate, which is unpaired whenever it is the last character in the
   * buffer, because a pair reaches the buffer in order.
   */
  private void dropSplitSurrogate() {
    int last = buf.length() - 1;
    if (last >= 0 && Character.isHighSurrogate(buf.charAt(last))) {
      buf.setLength(last);
      removed++;
    }
  }

  @Override
  public BoundedStringBuilder append(char c) {
    if (remaining() > 0) {
      buf.append(c);
    } else {
      recordDrop();
      dropSplitSurrogate();
    }
    return this;
  }

  @Override
  public BoundedStringBuilder append(@Nullable CharSequence csq) {
    CharSequence value = csq == null ? "null" : csq;
    return append(value, 0, value.length());
  }

  @Override
  public BoundedStringBuilder append(@Nullable CharSequence csq, int start, int end) {
    CharSequence value = csq == null ? "null" : csq;
    int accepted = Math.min(end - start, remaining());
    buf.append(value, start, start + accepted);
    if (accepted < end - start) {
      recordDrop();
      dropSplitSurrogate();
    }
    return this;
  }

  /**
   * Grows the buffer so that {@code chars} more characters fit without another copy, or so that the
   * rest of the limit does when {@code chars} exceeds it.
   *
   * <p>A producer that appends character by character, as escaping a literal does, otherwise makes
   * the buffer double its way to the final size. A hint that is negative or that overflowed is
   * ignored.</p>
   */
  public BoundedStringBuilder ensureRoomFor(int chars) {
    buf.ensureCapacity(buf.length() + Math.min(chars, remaining()));
    return this;
  }

  /**
   * Appends the decimal representation of {@code value}.
   */
  public BoundedStringBuilder append(int value) {
    if (remaining() >= MAX_INT_LENGTH) {
      buf.append(value);
      return this;
    }
    return append(Integer.toString(value));
  }

  /**
   * Returns the collected characters, ending with a truncation marker when characters were
   * discarded.
   */
  @Override
  public String toString() {
    if (!truncated) {
      return buf.toString();
    }
    // A truncated builder is a bounded one, so the limit is the budget the marker has to fit in.
    if (limit <= TRUNCATION_MARKER.length()) {
      return TRUNCATION_MARKER.substring(0, limit);
    }
    int keep = Math.min(buf.length(), limit - TRUNCATION_MARKER.length());
    if (keep > 0 && Character.isHighSurrogate(buf.charAt(keep - 1))) {
      // Cutting a surrogate pair in half would leave text that is not valid UTF-16, and a handler
      // that encodes the message would turn the lone surrogate into a replacement character.
      keep--;
    }
    StringBuilder result = new StringBuilder(keep + TRUNCATION_MARKER.length());
    result.append(buf, 0, keep);
    result.append(TRUNCATION_MARKER);
    return result.toString();
  }
}
