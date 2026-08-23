/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Pins what {@link VisibleBufferedInputStream} reports rather than reads: {@code available()}
 * answers from the buffer alone, and a scanned string is measured with its terminator.
 */
class VisibleBufferedInputStreamTest {
  private static final byte[] DATA = {1, 2, 3, 4, 5, 6, 7, 8};

  /**
   * Fails the test if anything asks how much it holds, so a call that reaches the wrapped stream
   * cannot pass unnoticed.
   */
  private static class RefusesToBeAsked extends ByteArrayInputStream {
    RefusesToBeAsked() {
      super(DATA);
    }

    @Override
    public synchronized int available() {
      throw new AssertionError("available() must not reach the wrapped stream while the buffer"
          + " still holds bytes");
    }
  }

  /**
   * Reports a count of its own, so what the buffered stream passes through can be told apart from
   * what it counts.
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
  void availableIsTheBufferedCountAloneWhileTheBufferHoldsBytes() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new RefusesToBeAsked(), 1024);
    in.ensureBytes(DATA.length);
    in.read();

    assertEquals(DATA.length - 1, in.available(),
        "a lower bound taken from the buffer, leaving out what the wrapped stream holds");
  }

  @Test
  void availableFallsThroughToTheWrappedStreamOnceTheBufferIsEmpty() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new Claims(500), 1024);

    assertEquals(500, in.available(), "nothing buffered yet");
  }

  @Test
  void aTerminatedStringIsMeasuredIncludingItsTerminator() throws IOException {
    InputStream source = new ByteArrayInputStream(new byte[]{'a', 'b', 'c', 0, 'd'});
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(source, 1024);

    assertEquals(4, in.scanCStringLength(), "length of \"abc\" plus its terminator");
  }
}
