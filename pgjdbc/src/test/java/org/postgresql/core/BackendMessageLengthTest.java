/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.util.HostSpec;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Locale;

/**
 * A backend message is read only within the bounds of its declared length. The message length is
 * checked against the range its message type allows, and each DataRow column length against the
 * bytes the message leaves. A reader that stops short of the declared end, or runs past it, is
 * refused at the next message type.
 *
 * <p>The cases sit on both sides of each boundary, and the bytes come from a canned socket holding
 * a fixed sequence rather than from a server.</p>
 */
@Isolated("Uses Locale.setDefault")
class BackendMessageLengthTest {

  // The assertions match on message text, which GT.tr translates once these strings are
  // localized.
  private static Locale defaultLocale;

  @BeforeAll
  static void useRootLocale() {
    defaultLocale = Locale.getDefault();
    Locale.setDefault(Locale.ROOT);
  }

  @AfterAll
  static void restoreLocale() {
    Locale.setDefault(defaultLocale);
  }

  private static PGStream streamOf(byte[] bytes) throws IOException {
    return new PGStream(new CannedSocketFactory(bytes), new HostSpec("localhost", 5432), 0, 8192);
  }

  private static byte[] int4(int... values) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int value : values) {
      out.write(value >>> 24);
      out.write(value >>> 16);
      out.write(value >>> 8);
      out.write(value);
    }
    return out.toByteArray();
  }

  /**
   * The limit under test is {@link PGStream#MAX_SMALL_MESSAGE_LENGTH}, so
   * {@link PGStream#MAX_MESSAGE_LENGTH}, the largest length any message may declare, belongs
   * among the refused values.
   */
  @Test
  void rejectsLengthsOutsideTheRange() throws IOException {
    int max = PGStream.MAX_SMALL_MESSAGE_LENGTH;
    int[] rejected = {Integer.MIN_VALUE, -1, 0, 3, 4, max + 1, PGStream.MAX_MESSAGE_LENGTH,
        Integer.MAX_VALUE};

    for (int length : rejected) {
      PGStream stream = streamOf(int4(length));
      IOException e = assertThrows(IOException.class,
          () -> stream.receiveMessageLength("ErrorResponse", 5, max), "length " + length + " must be refused");
      assertTrue(e.getMessage().contains(String.valueOf(length)), e.getMessage());
    }
  }

  @Test
  void acceptsLengthsInsideTheRange() throws IOException {
    int max = PGStream.MAX_SMALL_MESSAGE_LENGTH;
    for (int length : new int[]{5, 6, 1024, max}) {
      assertEquals(length, streamOf(int4(length)).receiveMessageLength("ErrorResponse", 5, max));
    }
  }

  @Test
  void namesTheMessageInTheError() throws IOException {
    PGStream stream = streamOf(int4(3));
    IOException e = assertThrows(IOException.class,
        () -> stream.receiveMessageLength("ErrorResponse", 5, 100));
    assertTrue(e.getMessage().contains("ErrorResponse"), e.getMessage());
  }

  /** Subtracting the 4 length bytes from this value wraps to a positive two gigabyte size. */
  @Test
  void rejectsTheWraparoundCopyDataLength() throws IOException {
    PGStream stream = streamOf(int4(Integer.MIN_VALUE));
    assertThrows(IOException.class,
        () -> stream.receiveMessageLength("CopyData", 4, PGStream.MAX_MESSAGE_LENGTH));
  }

  /** libpq accepts a zero length CopyData body. */
  @Test
  void acceptsAZeroLengthCopyDataBody() throws IOException {
    assertEquals(4, streamOf(int4(4)).receiveMessageLength("CopyData", 4, PGStream.MAX_MESSAGE_LENGTH));
  }

  @Test
  void readsAValidDataRow() throws IOException, SQLException {
    // length, field count, then a 3 byte column, a null column and an empty column.
    byte[] message = new byte[]{0, 0, 0, 21, 0, 3, 0, 0, 0, 3, 'a', 'b', 'c',
        (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 0, 0, 0};

    Tuple tuple = streamOf(message).receiveTupleV3();

    assertEquals(3, tuple.fieldCount());
    assertArrayEquals(new byte[]{'a', 'b', 'c'}, tuple.get(0));
    assertNull(tuple.get(1));
    assertArrayEquals(new byte[0], tuple.get(2));
  }

  /** Only -1 means a null column, so a length of -2 is refused. */
  @Test
  void rejectsAColumnLengthBelowNull() throws IOException {
    byte[] message = new byte[]{0, 0, 0, 10, 0, 1, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFE};
    PGStream stream = streamOf(message);
    IOException e = assertThrows(IOException.class, () -> stream.receiveTupleV3());
    assertTrue(e.getMessage().contains("does not fit"), e.getMessage());
  }

  @Test
  void rejectsAColumnThatRunsPastTheMessage() throws IOException {
    byte[] message = new byte[]{0, 0, 0, 10, 0, 1, 0, 16, 0, 0};
    PGStream stream = streamOf(message);
    IOException e = assertThrows(IOException.class, () -> stream.receiveTupleV3());
    // The assertion pins the refusal to the column length check rather than to end of stream.
    assertTrue(e.getMessage().contains("does not fit"), e.getMessage());
  }

  @Test
  void rejectsADataRowWhoseColumnsUnderrunItsEnvelope() throws IOException {
    // Of the declared 21 bytes, 4 length + 2 count + 4 column length leave 11 for column data.
    // The one column declares 7, so 4 stay unread.
    byte[] message = new byte[]{0, 0, 0, 21, 0, 1, 0, 0, 0, 7, 'a', 'b', 'c', 'd', 'e', 'f', 'g'};
    PGStream stream = streamOf(message);
    IOException e = assertThrows(IOException.class, () -> stream.receiveTupleV3());
    assertTrue(e.getMessage().contains("unread"), e.getMessage());
  }

  /** The declared length leaves three bytes for column data, and the one column declares three. */
  @Test
  void acceptsADataRowThatConsumesItsEnvelopeExactly() throws IOException, SQLException {
    byte[] message = new byte[]{0, 0, 0, 13, 0, 1, 0, 0, 0, 3, 'a', 'b', 'c'};

    Tuple tuple = streamOf(message).receiveTupleV3();

    assertEquals(1, tuple.fieldCount());
    assertArrayEquals(new byte[]{'a', 'b', 'c'}, tuple.get(0));
  }

  @Test
  void rejectsADataRowTooShortForItsColumnCount() throws IOException {
    // The message declares 100 columns, and its 10 bytes cannot hold their lengths.
    byte[] message = new byte[]{0, 0, 0, 10, 0, 100, 0, 0, 0, 0};
    PGStream stream = streamOf(message);
    IOException e = assertThrows(IOException.class, () -> stream.receiveTupleV3());
    assertTrue(e.getMessage().contains("cannot hold"), e.getMessage());
  }

  @Test
  void marksTheStreamBrokenWhenALengthIsRefused() throws IOException {
    PGStream stream = streamOf(int4(Integer.MAX_VALUE));

    assertThrows(IOException.class,
        () -> stream.receiveMessageLength("ErrorResponse", 5, PGStream.MAX_SMALL_MESSAGE_LENGTH));

    assertTrue(stream.isBroken(), "the refusal must mark the stream broken");
    // PgConnection.isClosed() reports the broken stream, so a pool does not reuse the connection.
    assertTrue(stream.isClosed(), "a broken stream must report itself closed");
  }

  @Test
  void marksTheStreamBrokenWhenADataRowIsRefused() throws IOException {
    byte[] message = new byte[]{0, 0, 0, 10, 0, 1, 0, 16, 0, 0};
    PGStream stream = streamOf(message);

    assertThrows(IOException.class, () -> stream.receiveTupleV3());

    assertTrue(stream.isBroken(), "the refusal must mark the stream broken");
  }

  /** A read after a refusal reports the refusal, not whatever fails next. */
  @Test
  void refusesToReadPastABrokenStream() throws IOException {
    // The declared length 3 is below the 5 byte minimum, and the 'Z' after it is a message type
    // the driver reads.
    PGStream stream = streamOf(new byte[]{0, 0, 0, 3, 'Z'});

    assertThrows(IOException.class,
        () -> stream.receiveMessageLength("ErrorResponse", 5, PGStream.MAX_SMALL_MESSAGE_LENGTH));

    IOException e = assertThrows(IOException.class, () -> stream.receiveMessageType());
    assertTrue(e.getMessage().contains("protocol violation"), e.getMessage());
  }

  @Test
  void leavesAnAcceptedLengthAlone() throws IOException {
    PGStream stream = streamOf(int4(PGStream.MAX_SMALL_MESSAGE_LENGTH));

    stream.receiveMessageLength("ErrorResponse", 5, PGStream.MAX_SMALL_MESSAGE_LENGTH);

    assertFalse(stream.isBroken());
    assertFalse(stream.isClosed());
  }

  @Test
  void rejectsAMessageWhoseReaderStoppedShort() throws IOException {
    // The declared 10 bytes leave 6 body bytes. The reader consumes 2 of them, and 'Z' follows.
    byte[] message = new byte[]{0, 0, 0, 10, 1, 2, 3, 4, 5, 6, 'Z'};
    PGStream stream = streamOf(message);

    stream.receiveMessageLength("NoticeResponse", 5, 100);
    stream.receiveInteger2();

    IOException e = assertThrows(IOException.class, () -> stream.receiveMessageType());
    assertTrue(e.getMessage().contains("stopped at byte"), e.getMessage());
    assertTrue(stream.isBroken());
  }

  /** The declared length leaves six body bytes and the reader skips six. */
  @Test
  void acceptsAMessageConsumedExactly() throws IOException {
    byte[] message = new byte[]{0, 0, 0, 10, 1, 2, 3, 4, 5, 6, 'Z'};
    PGStream stream = streamOf(message);

    stream.receiveMessageLength("NoticeResponse", 5, 100);
    stream.skip(6);

    assertEquals('Z', stream.receiveMessageType());
  }

  /**
   * The declared length leaves two body bytes and the reader skips four, so it ends two
   * bytes past the end of the message.
   */
  @Test
  void rejectsAMessageWhoseReaderRanPast() throws IOException {
    byte[] message = new byte[]{0, 0, 0, 6, 1, 2, 3, 4, 'Z'};
    PGStream stream = streamOf(message);

    stream.receiveMessageLength("NoticeResponse", 5, 100);
    stream.skip(4);

    assertThrows(IOException.class, () -> stream.receiveMessageType());
  }

  /** There is no previous message to check before the first one on a connection. */
  @Test
  void acceptsAMessageTypeWithNoMessageOutstanding() throws IOException {
    assertEquals('R', streamOf(new byte[]{'R'}).receiveMessageType());
  }

  @Test
  void rejectsAStringThatRunsPastItsMessage() throws IOException {
    // The declared 9 bytes leave 5 body bytes with no terminator among them. The terminator is
    // the next byte, one past the end of the message.
    byte[] message = new byte[]{0, 0, 0, 9, 'a', 'b', 'c', 'd', 'e', 0};
    PGStream stream = streamOf(message);

    stream.receiveMessageLength("ParameterStatus", 6, 100);

    IOException e = assertThrows(IOException.class, () -> stream.receiveString());
    assertTrue(e.getMessage().contains("terminator"), e.getMessage());
    assertTrue(stream.isBroken());
  }

  /** The terminator is the last byte of the message, so the scan succeeds at its limit. */
  @Test
  void acceptsAStringThatEndsOnTheLastByteOfItsMessage() throws IOException {
    byte[] message = new byte[]{0, 0, 0, 9, 'a', 'b', 'c', 'd', 0};
    PGStream stream = streamOf(message);

    stream.receiveMessageLength("ParameterStatus", 6, 100);

    assertEquals("abcd", stream.receiveString());
  }

  @Test
  void capsThePreAuthenticationMessageBelowTheBufferedOne() {
    assertTrue(PGStream.MAX_PRE_AUTH_MESSAGE_LENGTH < PGStream.MAX_BUFFERED_MESSAGE_LENGTH);
    // libpq bounds the declared length, which counts its own 4 bytes, so the driver uses the
    // same 30000.
    assertEquals(30000, PGStream.MAX_PRE_AUTH_MESSAGE_LENGTH);
  }
}
