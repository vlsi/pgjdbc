/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * Fails when {@link VisibleBufferedInputStream#skip(long)} miscounts what it discarded, calls the
 * wrapped stream's {@link InputStream#skip(long)}, or applies a different socket-timeout rule than
 * the rest of the stream.
 *
 * <p>The caller subtracts the count a discard returns from the protocol message it is working
 * through, so a count short by one leaves the connection off a message boundary. Discarding goes
 * through the buffer, which puts it under the timeout rule the rest of the stream follows: a
 * timeout the caller did not ask for through {@link PGStream#setNetworkTimeout(int)} is waited
 * out, and one it did ask for is thrown. The wrapped stream's own {@code skip} is never called,
 * so {@link Chunked} and {@link TimesOut} throw from their own {@code skip} rather than returning
 * a count.</p>
 *
 * <p>The count survives only a normal return. A discard that fails part-way loses the count with
 * the exception, and the bytes it took are gone;
 * {@link #aDiscardInterruptedPartWayIsNotRestartable()} pins that.</p>
 */
// Discarding runs in a loop, so a wrong loop condition spins here. The timeout runs the test on a
// separate thread because the default thread mode measures the elapsed time only after the test
// method returns, and a spinning test never returns
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class VisibleBufferedInputStreamSkipAccountingTest {
  /**
   * Payload the wrapped streams serve. Offsets that are adjacent, or a multiple of 256 apart, hold
   * different bytes. The count {@code skip} returns pins the position, and the byte read after a
   * discard cross-checks that count.
   */
  private static final byte[] DATA = new byte[4096];

  /**
   * Largest read a wrapped stream serves, in bytes. It is smaller than {@link #DATA}, so a
   * discard spans several reads and a count lost on one of them changes what {@code skip} returns.
   */
  private static final int CHUNK = 64;

  static {
    for (int i = 0; i < DATA.length; i++) {
      DATA[i] = (byte) (i * 7 + 1 + (i >> 8));
    }
  }

  /**
   * Serves {@link #DATA} in reads of at most {@link #CHUNK} bytes, counts those reads in
   * {@link #readCalls}, and refuses to skip.
   */
  private static class Chunked extends ByteArrayInputStream {
    int readCalls;

    Chunked() {
      super(DATA);
    }

    @Override
    public synchronized int read(byte[] b, int off, int len) {
      readCalls++;
      return super.read(b, off, Math.min(len, CHUNK));
    }

    @Override
    public synchronized long skip(long n) {
      throw new AssertionError("a discard goes through the buffer, so the wrapped stream's skip must not be called");
    }
  }

  /**
   * Throws {@link SocketTimeoutException} for the first {@code timeouts} reads, then serves
   * {@link #DATA} in reads of at most {@link #CHUNK} bytes. Refuses to skip.
   */
  private static class TimesOut extends InputStream {
    private int remainingTimeouts;
    private int pos;

    TimesOut(int timeouts) {
      this.remainingTimeouts = timeouts;
    }

    int timeoutsLeft() {
      return remainingTimeouts;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      int n = read(one, 0, 1);
      return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (remainingTimeouts > 0) {
        remainingTimeouts--;
        throw new SocketTimeoutException("Read timed out");
      }
      if (pos >= DATA.length) {
        return -1;
      }
      int n = Math.min(Math.min(len, CHUNK), DATA.length - pos);
      System.arraycopy(DATA, pos, b, off, n);
      pos += n;
      return n;
    }

    @Override
    public long skip(long n) {
      throw new AssertionError("a discard goes through the buffer, so the wrapped stream's skip must not be called");
    }
  }

  private static VisibleBufferedInputStream stream(InputStream wrapped) {
    return new VisibleBufferedInputStream(wrapped, 1024);
  }

  @Test
  void aDiscardServedFromTheBufferReadsNothing() throws IOException {
    Chunked wrapped = new Chunked();
    VisibleBufferedInputStream in = stream(wrapped);
    in.read();
    int readsAfterPriming = wrapped.readCalls;

    assertEquals(4, in.skip(4), "bytes discarded from the buffer");
    assertEquals(readsAfterPriming, wrapped.readCalls, "reads on the wrapped stream after a discard served from the buffer");
    assertEquals(DATA[5] & 0xFF, in.read(), "byte after the discard");
  }

  /**
   * A discard never grows the buffer on its own, because it asks for a single byte, and only when
   * the buffer is empty. {@link VisibleBufferedInputStream#ensureBytes(int)} does grow it, so a
   * discard runs against a buffer larger than the constructor asked for only after a caller has
   * read ahead.
   */
  @Test
  void aDiscardServedFromAGrownBufferReadsNothing() throws IOException {
    Chunked wrapped = new Chunked();
    VisibleBufferedInputStream in = stream(wrapped);
    byte[] beforeGrowing = in.getBuffer();
    in.ensureBytes(2000);
    int readsAfterGrowing = wrapped.readCalls;

    assertNotSame(beforeGrowing, in.getBuffer(), "buffer after a read-ahead larger than it held");
    assertEquals(1500, in.skip(1500), "bytes discarded from the grown buffer");
    assertEquals(readsAfterGrowing, wrapped.readCalls, "reads on the wrapped stream during the discard");
    assertEquals(DATA[1500] & 0xFF, in.read(), "byte after the discard");
  }

  @Test
  void aDiscardSpanningSeveralReadsCountsEveryByte() throws IOException {
    VisibleBufferedInputStream in = stream(new Chunked());
    in.read();

    assertEquals(1000, in.skip(1000), "bytes discarded across several reads");
    assertEquals(DATA[1001] & 0xFF, in.read(), "byte after the discard");
  }

  @Test
  void aDiscardPastEndOfStreamReportsWhatItGot() throws IOException {
    VisibleBufferedInputStream in = stream(new Chunked());

    assertEquals(DATA.length, in.skip(DATA.length + 16), "bytes discarded before end of stream");
    assertEquals(-1, in.read(), "read at end of stream");
  }

  @Test
  void aDiscardOfZeroOrLessDiscardsNothing() throws IOException {
    VisibleBufferedInputStream in = stream(new Chunked());
    in.read();

    assertEquals(0, in.skip(0), "skip(0)");
    assertEquals(0, in.skip(-4), "skip(-4)");
    assertEquals(1, in.getIndex(), "read position after a discard of zero or less");
    assertEquals(DATA[1] & 0xFF, in.read(), "byte after a discard of zero or less");
  }

  @Test
  void aTimeoutTheCallerDidNotAskForIsWaitedOut() throws IOException {
    TimesOut wrapped = new TimesOut(2);
    VisibleBufferedInputStream in = stream(wrapped);

    assertEquals(100, in.skip(100), "bytes discarded after two timeouts were waited out");
    assertEquals(0, wrapped.timeoutsLeft(), "timeouts left in the wrapped stream");
    assertEquals(DATA[100] & 0xFF, in.read(), "byte after the discard");
  }

  @Test
  void aTimeoutTheCallerAskedForIsThrown() {
    VisibleBufferedInputStream in = stream(new TimesOut(1));
    in.setTimeoutRequested(true);

    assertThrows(SocketTimeoutException.class, () -> in.skip(100),
        "a discard interrupted by a timeout the caller asked for");
  }

  /**
   * Fails when a discard interrupted part-way puts back the bytes it had already taken. The count
   * goes with the exception, so the test checks the read position instead.
   */
  @Test
  void aDiscardInterruptedPartWayIsNotRestartable() throws IOException {
    // Times out on the second read only, so the discard has already taken CHUNK bytes when it
    // fails and the test can read back the byte at the position it left
    TimesOut wrapped = new TimesOut(0) {
      private int reads;

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        if (reads++ == 1) {
          throw new SocketTimeoutException("Read timed out");
        }
        return super.read(b, off, len);
      }
    };
    VisibleBufferedInputStream in = stream(wrapped);
    in.setTimeoutRequested(true);

    assertThrows(SocketTimeoutException.class, () -> in.skip(CHUNK + 50),
        "a discard interrupted after it took some bytes");
    assertEquals(DATA[CHUNK] & 0xFF, in.read(),
        "byte at the position the failed discard left");
  }
}
