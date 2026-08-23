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
 * Fails when {@link VisibleBufferedInputStream#skip(long)} moves the read position by a count that
 * {@link InputStream#skip(long)} says it must ignore.
 *
 * <p>A count of zero or less has to skip nothing and return {@code 0}. The read position is
 * {@link VisibleBufferedInputStream#getIndex()}, an offset into the array that
 * {@link VisibleBufferedInputStream#getBuffer()} hands out, so a negative count drives it below
 * zero: {@code available()} then over-reports, {@code ensureBytes} reports success on a drained
 * buffer, and the read after that fails with {@link ArrayIndexOutOfBoundsException} rather than an
 * {@link IOException} the driver can turn into a connection error.</p>
 *
 * <p>Zero is here because it is the boundary the guard is written on, and because
 * {@link PGStream#skip(int)} never passes it down -- its loop does not run for a count of zero or
 * less, which is also why nothing above covers the negative case. Positive counts are covered
 * through {@code PGStreamSkipTest}, so a stricter guard that swallowed a real skip fails there.</p>
 */
class VisibleBufferedInputStreamSkipTest {
  /**
   * Payload the stream serves. Every byte differs from its neighbours, so a skip that lands at the
   * wrong offset is visible in the byte read after it.
   */
  private static final byte[] DATA = new byte[256];

  /**
   * Bytes the wrapped stream serves per read. Smaller than {@link #DATA} so that a read leaves the
   * buffer partly filled, which is the state {@code available()} is read in below.
   */
  private static final int CHUNK = 16;

  static {
    for (int i = 0; i < DATA.length; i++) {
      DATA[i] = (byte) (i * 7 + 1);
    }
  }

  /**
   * Serves at most {@link #CHUNK} bytes per read, the way a socket hands over one segment.
   */
  private static class Chunked extends ByteArrayInputStream {
    Chunked() {
      super(DATA);
    }

    @Override
    public synchronized int read(byte[] b, int off, int len) {
      return super.read(b, off, Math.min(len, CHUNK));
    }
  }

  private static VisibleBufferedInputStream stream() {
    return new VisibleBufferedInputStream(new Chunked(), 32);
  }

  @Test
  void negativeSkipOnAnEmptyBufferSkipsNothing() throws IOException {
    VisibleBufferedInputStream in = stream();

    assertEquals(0, in.skip(-4), "skip(-4) before anything is buffered");
    assertEquals(0, in.getIndex(), "read position after skip(-4)");
    assertEquals(DATA[0] & 0xFF, in.read(), "first byte after skip(-4)");
  }

  @Test
  void negativeSkipOnABufferedStreamSkipsNothing() throws IOException {
    VisibleBufferedInputStream in = stream();
    in.read();

    assertEquals(0, in.skip(-4), "skip(-4) after one byte was consumed");
    assertEquals(1, in.getIndex(), "read position after skip(-4)");
  }

  @Test
  void negativeSkipLeavesTheStreamReadable() throws IOException {
    VisibleBufferedInputStream in = stream();
    in.read();

    in.skip(-4);

    assertEquals(DATA[1] & 0xFF, in.read(), "byte after skip(-4)");
    assertEquals(DATA[2] & 0xFF, in.read(), "second byte after skip(-4)");
  }

  @Test
  void negativeSkipDoesNotInflateAvailable() throws IOException {
    VisibleBufferedInputStream in = stream();
    in.read();
    int available = in.available();

    in.skip(-4);

    assertEquals(available, in.available(), "available() after skip(-4)");
  }

  @Test
  void negativeSkipDoesNotMakeAnEmptyStreamLookReadable() throws IOException {
    VisibleBufferedInputStream in =
        new VisibleBufferedInputStream(new ByteArrayInputStream(new byte[0]), 32);

    assertEquals(0, in.skip(-4), "skip(-4) on an empty stream");
    assertEquals(-1, in.read(), "read() at the end of an empty stream");
  }

  /**
   * {@code (int) Long.MIN_VALUE} is {@code 0}, so the truncating cast left the read position alone
   * and reported the count itself as the number of bytes skipped.
   */
  @Test
  void theMostNegativeSkipSkipsNothing() throws IOException {
    VisibleBufferedInputStream in = stream();
    in.read();

    assertEquals(0, in.skip(Long.MIN_VALUE), "skip(Long.MIN_VALUE)");
    assertEquals(1, in.getIndex(), "read position after skip(Long.MIN_VALUE)");
    assertEquals(DATA[1] & 0xFF, in.read(), "byte after skip(Long.MIN_VALUE)");
  }

  @Test
  void zeroSkipSkipsNothing() throws IOException {
    VisibleBufferedInputStream in = stream();
    in.read();

    assertEquals(0, in.skip(0), "skip(0)");
    assertEquals(1, in.getIndex(), "read position after skip(0)");
    assertEquals(DATA[1] & 0xFF, in.read(), "byte after skip(0)");
  }
}
