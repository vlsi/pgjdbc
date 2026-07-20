/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.PrimitiveTextSink;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;

/**
 * {@link Appendable} wrapper that emits characters into a delegate, escaping any
 * {@code "} or {@code \\} character in one of the two PostgreSQL container styles.
 *
 * <p>{@code array_out} escapes both characters with a leading backslash
 * ({@code "} → {@code \"}, {@code \\} → {@code \\\\}); {@code record_out} doubles
 * them instead ({@code "} → {@code ""}, {@code \\} → {@code \\\\}). The default
 * constructor keeps {@link EscapeStyle#ARRAY}; pass {@link EscapeStyle#RECORD} for
 * composite fields. {@link LiteralCursor} accepts both on decode, so this only
 * matters when the produced text must match the server byte-for-byte.</p>
 *
 * <p>This wrapper does not add the surrounding quotes for an array element or
 * composite field; callers are responsible for writing those quotes. The
 * wrapper only applies one escaping layer. Wrapping an already escaped sink is
 * intentional and represents nested text formats, for example an array element
 * that is a composite whose field is itself an array.</p>
 *
 * <p>A caller that already holds the whole value as a {@link CharSequence} does
 * not need the wrapper: {@link #appendQuotedArrayStyle} and
 * {@link #appendQuotedRecordStyle} write it quoted and escaped in one call,
 * without allocating.</p>
 *
 * <p>These styles cover container text only. SQL lexical syntax escapes
 * differently and belongs to {@link org.postgresql.core.Utils}: a quoted
 * identifier gives {@code "} no special meaning beyond doubling and leaves
 * {@code \\} alone, so running one through {@code appendQuotedRecordStyle}
 * would silently rename it.</p>
 */
public final class ContainerTextEscaper implements PrimitiveTextSink {

  /**
   * Which of the two container escaping conventions to apply. Both escape exactly {@code "} and
   * {@code \\}, and differ only in the character they emit before it.
   */
  public enum EscapeStyle {
    /** {@code array_out} and {@code hstore_out}: prefix the character with a backslash. */
    ARRAY,
    /** {@code record_out} and {@code range_out}: double the character. */
    RECORD;

    /**
     * The character to emit before {@code c}, which the caller has already established is one of
     * the two escapable characters.
     */
    char escapePrefixFor(char c) {
      return this == RECORD ? c : '\\';
    }
  }

  private final Appendable delegate;
  private final EscapeStyle style;

  public ContainerTextEscaper(Appendable delegate, EscapeStyle style) {
    this.delegate = delegate;
    this.style = style;
  }

  @Override
  public Appendable append(@Nullable CharSequence csq) throws IOException {
    if (csq == null) {
      return append("null");
    }
    return append(csq, 0, csq.length());
  }

  @Override
  public Appendable append(@Nullable CharSequence csq, int start, int end) throws IOException {
    if (csq == null) {
      csq = "null";
    }
    for (int i = start; i < end; i++) {
      append(csq.charAt(i));
    }
    return this;
  }

  @Override
  public Appendable append(char c) throws IOException {
    if (c == '"' || c == '\\') {
      delegate.append(style.escapePrefixFor(c));
    }
    delegate.append(c);
    return this;
  }

  @Override
  public PrimitiveTextSink append(int value) throws IOException {
    PrimitiveTextSink.appendInt(delegate, value);
    return this;
  }

  @Override
  public PrimitiveTextSink append(long value) throws IOException {
    PrimitiveTextSink.appendLong(delegate, value);
    return this;
  }

  @Override
  public PrimitiveTextSink append(float value) throws IOException {
    PrimitiveTextSink.appendFloat(delegate, value);
    return this;
  }

  @Override
  public PrimitiveTextSink append(double value) throws IOException {
    PrimitiveTextSink.appendDouble(delegate, value);
    return this;
  }

  /**
   * Writes {@code value} to {@code out} enclosed in double quotes, escaping every {@code "} and
   * {@code \\} with a leading backslash. This is the style {@code array_out} and {@code hstore_out}
   * produce.
   *
   * <p>The quotes are unconditional: the two formats that use this style quote every element they
   * emit. A caller that quotes conditionally has to make that decision itself.</p>
   *
   * @param out the sink to write to
   * @param value the value to quote and escape
   * @throws IOException if {@code out} fails
   */
  public static void appendQuotedArrayStyle(Appendable out, CharSequence value) throws IOException {
    appendQuoted(out, value, EscapeStyle.ARRAY);
  }

  /**
   * {@link StringBuilder} overload of {@link #appendQuotedArrayStyle(Appendable, CharSequence)},
   * for callers that build the literal in memory and have no {@link IOException} to report.
   *
   * @param out the buffer to write to
   * @param value the value to quote and escape
   */
  public static void appendQuotedArrayStyle(StringBuilder out, CharSequence value) {
    appendQuotedUnchecked(out, value, EscapeStyle.ARRAY);
  }

  /**
   * Writes {@code value} to {@code out} enclosed in double quotes, escaping every {@code "} and
   * {@code \\} by doubling it. This is the style {@code record_out} and {@code range_out} produce.
   *
   * <p>Both formats quote conditionally, so the caller decides whether a value needs quoting before
   * calling this.</p>
   *
   * @param out the sink to write to
   * @param value the value to quote and escape
   * @throws IOException if {@code out} fails
   */
  public static void appendQuotedRecordStyle(Appendable out, CharSequence value)
      throws IOException {
    appendQuoted(out, value, EscapeStyle.RECORD);
  }

  /**
   * {@link StringBuilder} overload of {@link #appendQuotedRecordStyle(Appendable, CharSequence)},
   * for callers that build the literal in memory and have no {@link IOException} to report.
   *
   * @param out the buffer to write to
   * @param value the value to quote and escape
   */
  public static void appendQuotedRecordStyle(StringBuilder out, CharSequence value) {
    appendQuotedUnchecked(out, value, EscapeStyle.RECORD);
  }

  private static void appendQuotedUnchecked(StringBuilder out, CharSequence value,
      EscapeStyle style) {
    try {
      appendQuoted(out, value, style);
    } catch (IOException e) {
      throw new AssertionError("StringBuilder.append never throws IOException", e);
    }
  }

  private static void appendQuoted(Appendable out, CharSequence value, EscapeStyle style)
      throws IOException {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"' || c == '\\') {
        out.append(style.escapePrefixFor(c));
      }
      out.append(c);
    }
    out.append('"');
  }
}
