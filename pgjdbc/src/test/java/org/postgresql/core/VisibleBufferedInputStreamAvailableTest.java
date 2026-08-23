/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Fails when {@link VisibleBufferedInputStream#available()} leaves out what the wrapped stream is
 * holding, or when it overflows adding the two together.
 *
 * <p>A caller reads {@code available()} to size the next read, so a count short by the contents of
 * the socket costs a round trip per buffer. Reporting only the buffered bytes did exactly that
 * whenever anything was buffered at all, which is the ordinary state part-way through a message.
 * The sum is an upper estimate either way, since {@link InputStream#available()} promises nothing
 * beyond what can be read without blocking.</p>
 */
class VisibleBufferedInputStreamAvailableTest {
  private static final byte[] DATA = {1, 2, 3, 4, 5, 6, 7, 8};

  /**
   * Reports a fixed count regardless of what it holds, so the sum can be driven to its edge
   * without a payload that size.
   */
  private static class Claims extends ByteArrayInputStream {
    private final int claimed;

    Claims(int claimed) {
      super(DATA);
      this.claimed = claimed;
    }

    @Override
    public synchronized int available() {
      return claimed;
    }
  }

  @Test
  void availableAddsTheStreamToTheBuffer() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new Claims(500), 1024);
    in.ensureBytes(DATA.length);
    in.read();

    assertEquals(DATA.length - 1 + 500, in.available(),
        "buffered bytes plus what the wrapped stream reports");
  }

  @Test
  void availableWithNothingBufferedIsWhatTheStreamReports() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new Claims(500), 1024);

    assertEquals(500, in.available(), "nothing buffered yet");
  }

  @Test
  void availableSaturatesRatherThanOverflowing() throws IOException {
    VisibleBufferedInputStream in =
        new VisibleBufferedInputStream(new Claims(Integer.MAX_VALUE), 1024);
    in.ensureBytes(DATA.length);

    assertEquals(Integer.MAX_VALUE, in.available(),
        "a wrapped stream claiming everything must not wrap the sum negative");
  }

  @Test
  void availableIsNeverNegative() throws IOException {
    VisibleBufferedInputStream in =
        new VisibleBufferedInputStream(new Claims(Integer.MAX_VALUE), 1024);
    in.ensureBytes(DATA.length);

    assertTrue(in.available() >= 0, "available() must not report a negative count");
  }

  /**
   * A stream that ends before the terminator, which is the one case
   * {@link VisibleBufferedInputStream#scanCStringLength()} reports rather than returns.
   */
  @Test
  void anUnterminatedStringSaysWhyItFailed() {
    VisibleBufferedInputStream in =
        new VisibleBufferedInputStream(new ByteArrayInputStream("abc".getBytes()), 1024);

    EOFException e = assertThrows(EOFException.class, in::scanCStringLength,
        "a string the stream never terminates");
    assertNotNull(e.getMessage(), "the end of the stream is reported with a reason");
  }

  @Test
  void aTerminatedStringIsMeasuredIncludingItsTerminator() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(
        new ByteArrayInputStream(new byte[]{'a', 'b', 'c', 0, 'd'}), 1024);

    assertEquals(4, in.scanCStringLength(), "length of \"abc\" plus its terminator");
  }
}
