/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.Oid;
import org.postgresql.util.ByteStreamWriter;
import org.postgresql.util.internal.BoundedStringBuilder;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/**
 * Fails when a bound parameter renders into more characters than the sink accepts.
 *
 * <p>{@link SimpleParameterList#appendTo} feeds the protocol trace, so a parameter has to respect
 * the budget of the sink it writes to whatever the application bound: a value that outgrows the
 * budget is cut short or replaced by its size, and no intermediate string of the value's own size
 * is built along the way. An unbounded sink keeps the literal
 * {@link SimpleParameterList#toString(int, SqlSerializationContext)} returns, because
 * {@code PreparedStatement.toString()} and simple query mode read it as SQL.</p>
 */
class SimpleParameterListTruncationTest {
  private static final SqlSerializationContext CONTEXT = SqlSerializationContext.of(true, true);

  private static final int LIMIT = 100;

  private static final String FIELD_MARKER = "...";

  /**
   * U+1F600, which UTF-16 stores as a surrogate pair.
   */
  private static final String EMOJI = new String(Character.toChars(0x1F600));

  private static final TypeTransferModeRegistry TEXT_ONLY = new TypeTransferModeRegistry() {
    @Override
    public boolean useBinaryForSend(int oid) {
      return false;
    }

    @Override
    public boolean useBinaryForReceive(int oid) {
      return false;
    }
  };

  @Test
  void smallValueIsRenderedInFull() throws SQLException {
    SimpleParameterList params = new SimpleParameterList(1, TEXT_ONLY);
    params.setStringParameter(1, "it's fine", Oid.VARCHAR);

    assertEquals("('it''s fine')", render(params, LIMIT));
  }

  @Test
  void longTextKeepsItsPrefix() throws SQLException {
    SimpleParameterList params = new SimpleParameterList(1, TEXT_ONLY);
    params.setStringParameter(1, repeat('a', 1_000_000), Oid.VARCHAR);

    BoundedStringBuilder sb = new BoundedStringBuilder(LIMIT);
    params.appendTo(sb, 1, CONTEXT);

    assertTrue(sb.isTruncated(), "isTruncated");
    String rendered = sb.toString();
    assertEquals(LIMIT, rendered.length(), "rendered length");
    assertTrue(rendered.startsWith("('aaa"), () -> "rendered value should start with the "
        + "parameter's own text: " + rendered);
  }

  @Test
  void longByteaIsRenderedAsItsSize() throws SQLException {
    SimpleParameterList params = new SimpleParameterList(1, TEXT_ONLY);
    params.setBytea(1, new byte[1_000_000], 0, 1_000_000);

    assertEquals("<1000000 bytes>", render(params, LIMIT));
  }

  @Test
  void longHexStringIsRenderedAsItsLength() throws SQLException {
    SimpleParameterList params = new SimpleParameterList(1, TEXT_ONLY);
    params.setStringParameter(1, "\\x" + repeat('a', 1_000_000), Oid.BYTEA);

    assertEquals("<1000002 hex characters>", render(params, LIMIT));
  }

  @Test
  void byteaThatFitsIsRenderedAsALiteral() throws SQLException {
    SimpleParameterList params = new SimpleParameterList(1, TEXT_ONLY);
    params.setBytea(1, new byte[]{0x1a, 0x2b}, 0, 2);

    assertEquals("'\\x1a2b'::bytea", render(params, LIMIT));
  }

  @Test
  void aFieldBudgetBoundsAByteaWithNoMessageLimit() throws SQLException {
    SimpleParameterList params = new SimpleParameterList(1, TEXT_ONLY);
    params.setBytea(1, new byte[1_000_000], 0, 1_000_000);

    // What decides whether the literal may be built is the room the sink has left, not whether a
    // message limit was configured: an open field bounds an otherwise unlimited builder.
    BoundedStringBuilder sb = new BoundedStringBuilder(BoundedStringBuilder.UNLIMITED);
    sb.beginField(LIMIT);
    params.appendTo(sb, 1, CONTEXT);
    sb.endField();

    assertEquals("<1000000 bytes>", sb.toString());
  }

  @Test
  void aCutFieldIsAlwaysAPrefixOfTheWholeLiteral() throws SQLException {
    SimpleParameterList params = new SimpleParameterList(1, TEXT_ONLY);
    params.setStringParameter(1, repeat(EMOJI, 8), Oid.VARCHAR);
    String whole = params.toString(1, CONTEXT);

    // Every budget across the pairs, so no single one can sit where an artifact happens to hide.
    for (int budget = 1; budget <= 12; budget++) {
      BoundedStringBuilder sb = new BoundedStringBuilder(BoundedStringBuilder.UNLIMITED);
      sb.beginField(budget);
      params.appendTo(sb, 1, CONTEXT);
      sb.endField();

      String rendered = sb.toString();
      int cut = budget;
      assertTrue(rendered.endsWith(FIELD_MARKER),
          () -> "a budget of " + cut + " cannot hold the literal, so it must be marked: " + rendered);
      String kept = rendered.substring(0, rendered.length() - FIELD_MARKER.length());
      assertTrue(whole.startsWith(kept),
          () -> "a budget of " + cut + " kept <" + kept + ">, which is not a prefix of <" + whole + ">");
      assertNoLoneSurrogate(kept);
    }
  }

  @Test
  void unboundedSinkRendersTheWholeLiteral() throws SQLException {
    SimpleParameterList params = new SimpleParameterList(1, TEXT_ONLY);
    params.setBytea(1, new byte[100], 0, 100);

    BoundedStringBuilder sb = new BoundedStringBuilder(BoundedStringBuilder.UNLIMITED);
    params.appendTo(sb, 1, CONTEXT);

    assertFalse(sb.isTruncated(), "isTruncated");
    assertEquals(params.toString(1, CONTEXT), sb.toString());
    assertEquals(2 * 100 + "'\\x".length() + "'::bytea".length(), sb.toString().length(),
        "a bytea literal spells out two hex digits per byte");
  }

  @Test
  void unreadStreamIsNotConsumed() throws SQLException {
    SimpleParameterList params = new SimpleParameterList(1, TEXT_ONLY);
    byte[] data = new byte[1_000_000];
    params.setBytea(1, new ByteArrayInputStream(data), data.length);

    // An idempotent rendering leaves the stream for the send path, so the size never reaches the
    // trace, whatever the sink's budget is.
    assertEquals("?", render(params, LIMIT));
    assertEquals("?", render(params, BoundedStringBuilder.UNLIMITED));
  }

  @Test
  void longWriterIsRenderedAsItsSize() throws SQLException {
    SimpleParameterList params = new SimpleParameterList(1, TEXT_ONLY);
    params.setBytea(1, new FailingWriter(1_000_000));

    assertEquals("<1000000 bytes>", render(params, LIMIT),
        "an oversized writer must not be invoked");
  }

  @Test
  void nullAndUnsetParametersAreUnaffected() throws SQLException {
    SimpleParameterList params = new SimpleParameterList(2, TEXT_ONLY);
    params.setNull(1, Oid.VARCHAR);

    assertEquals("(NULL)", render(params, LIMIT));
    assertEquals("?", renderIndex(params, 2, LIMIT));
  }

  private static String render(SimpleParameterList params, int limit) {
    return renderIndex(params, 1, limit);
  }

  private static String renderIndex(SimpleParameterList params, int index, int limit) {
    BoundedStringBuilder sb = new BoundedStringBuilder(limit);
    params.appendTo(sb, index, CONTEXT);
    return sb.toString();
  }

  /**
   * Fails when {@code value} is not well-formed UTF-16. An unpaired surrogate does not survive a
   * round trip through UTF-8, which is what a log handler that encodes the message would do to it.
   */
  private static void assertNoLoneSurrogate(String value) {
    assertEquals(value, new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
        "value is not well-formed UTF-16");
  }

  private static String repeat(String value, int count) {
    StringBuilder sb = new StringBuilder(value.length() * count);
    for (int i = 0; i < count; i++) {
      sb.append(value);
    }
    return sb.toString();
  }

  private static String repeat(char c, int count) {
    byte[] bytes = new byte[count];
    java.util.Arrays.fill(bytes, (byte) c);
    return new String(bytes, StandardCharsets.US_ASCII);
  }

  /**
   * Reports a length without being able to produce the bytes, which is how a caller-supplied writer
   * that is too large to render must be treated.
   */
  private static final class FailingWriter implements ByteStreamWriter {
    private final int length;

    private FailingWriter(int length) {
      this.length = length;
    }

    @Override
    public int getLength() {
      return length;
    }

    @Override
    public void writeTo(ByteStreamTarget target) throws IOException {
      OutputStream unused = target.getOutputStream();
      throw new IOException("the writer must not be invoked for a trace message");
    }
  }
}
