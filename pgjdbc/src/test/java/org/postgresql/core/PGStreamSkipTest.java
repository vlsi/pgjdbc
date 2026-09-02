/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.util.HostSpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;

/**
 * Fails when {@link PGStream#skip(int)} does not discard exactly the bytes it was asked for.
 *
 * <p>Discarding goes through {@link InputStream#skip(long)}, which may return zero while more
 * bytes are still on the way, and a stream that has ended returns zero from every skip. The
 * driver has to tell the two apart: throw {@link EOFException} once the stream has ended, and
 * keep discarding while it has not. Treating every zero as the end breaks a usable connection;
 * treating none of them as the end never finishes. Discarding the wrong number of bytes leaves
 * the connection off a message boundary, which a later read reports as a protocol error.</p>
 *
 * <p>{@link CountingStream} stands in for what the {@code socketFactory} connection property may
 * put under the driver: an input stream the driver did not write.</p>
 */
// A skip that never finishes would hang this test, and the separate thread is what turns the
// hang into a failure: the default mode only checks the clock once the test method returns,
// which a hung test never does
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PGStreamSkipTest {
  /**
   * Payload the stream serves. Each of the 256 byte values appears once, so the byte read after a
   * skip identifies the offset the skip landed on.
   */
  private static final byte[] DATA = new byte[256];

  static {
    for (int i = 0; i < DATA.length; i++) {
      DATA[i] = (byte) (i * 7 + 1);
    }
  }

  /**
   * How many bytes of a requested skip a wrapped stream discards per call.
   */
  enum SkipStyle {
    /** Delegates the skip to the wrapped stream. This is the ordinary case. */
    HONEST,
    /** Skips nothing, ever, which {@link InputStream#skip(long)} permits. */
    REFUSES,
    /** Skips a single byte per call, so progress is real but slow. */
    ONE_AT_A_TIME
  }

  @Test
  void skipDiscardsExactlyTheRequestedBytes() throws Exception {
    // A zero-byte skip never calls down at all, and the buffer under PGStream starts empty, so
    // every other size here reaches the wrapped stream on the first call
    for (SkipStyle style : SkipStyle.values()) {
      for (int size : new int[]{0, 1, 100, DATA.length - 1, DATA.length}) {
        CountingStream source = new CountingStream(new ByteArrayInputStream(DATA), style);
        try (PGStream stream = openStream(source)) {
          String label = style + " skip=" + size;
          stream.skip(size);
          if (size < DATA.length) {
            assertEquals(DATA[size] & 0xFF, stream.receiveChar(), label + ": byte after the skip");
          }
          assertTrue(source.skipCalls > 0 || size == 0,
              label + ": the wrapped stream must have been asked to skip");
        }
      }
    }
  }

  @Test
  void skipReportsAStreamThatEndsEarly() throws Exception {
    for (SkipStyle style : SkipStyle.values()) {
      CountingStream source = new CountingStream(new ByteArrayInputStream(DATA), style);
      try (PGStream stream = openStream(source)) {
        assertThrows(EOFException.class, () -> stream.skip(DATA.length + 1),
            style + ": a stream that ends before the count must be reported, not waited on");
      }
    }
  }

  /**
   * Fails when a skip over a stream that refuses to skip degrades to one read per byte.
   *
   * <p>The read that breaks the refusal fills the buffer, and the next skip drains it in one
   * call.</p>
   */
  @Test
  void skipDoesNotFallBackToReadingByteByByte() throws Exception {
    byte[] payload = new byte[50000];
    CountingStream source =
        new CountingStream(new ByteArrayInputStream(payload), SkipStyle.REFUSES);
    try (PGStream stream = openStream(source)) {
      stream.skip(payload.length);
      assertTrue(source.readCalls < 100,
          "expected the buffer to carry the skip, got " + source.readCalls + " reads");
    }
  }

  /**
   * Opens a {@link PGStream} that reads from {@code source}, with no server behind it. The
   * {@code 8192} argument sizes the send buffer, not the buffer the driver reads through.
   */
  private static PGStream openStream(InputStream source) throws IOException {
    return new PGStream(new FixedSocketFactory(source), new HostSpec("localhost", 5432), 0, 8192);
  }

  /**
   * Counts the skip and read calls the driver makes, and skips as {@link SkipStyle} prescribes.
   *
   * <p>{@link #skip(long)} throws {@link IllegalStateException} once more than
   * {@link #ZERO_SKIP_LIMIT} skips in a row have returned zero with nothing read in between. A
   * caller that ignores a zero skip fails the test there, with a message that states what it
   * did, rather than spinning until the class timeout ends the test.</p>
   */
  static final class CountingStream extends FilterInputStream {
    /**
     * Consecutive zero-returning skips that count as a caller making no progress. A read resets
     * the count, because a caller that reads after a zero skip has made progress.
     */
    static final int ZERO_SKIP_LIMIT = 10;

    private final SkipStyle style;
    private int zeroSkipsInARow;
    int skipCalls;
    int readCalls;

    CountingStream(InputStream in, SkipStyle style) {
      super(in);
      this.style = style;
    }

    @Override
    public long skip(long n) throws IOException {
      skipCalls++;
      long skipped;
      switch (style) {
        case HONEST:
          skipped = super.skip(n);
          break;
        case ONE_AT_A_TIME:
          skipped = n == 0 ? 0 : super.skip(1);
          break;
        default:
          skipped = 0;
          break;
      }
      if (skipped > 0 || n == 0) {
        zeroSkipsInARow = 0;
        return skipped;
      }
      if (++zeroSkipsInARow > ZERO_SKIP_LIMIT) {
        throw new IllegalStateException("skip() returned 0 " + zeroSkipsInARow
            + " times in a row and the caller kept calling skip() without reading anything."
            + " A caller that does that makes no progress and never stops.");
      }
      return 0;
    }

    @Override
    public int read() throws IOException {
      readCalls++;
      zeroSkipsInARow = 0;
      return super.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      readCalls++;
      zeroSkipsInARow = 0;
      return super.read(b, off, len);
    }
  }

  /**
   * Creates sockets that read from the stream given to the constructor, so no server is involved.
   * Anything the driver writes goes to a buffer nothing reads.
   */
  static final class FixedSocketFactory extends SocketFactory {
    private final InputStream input;

    FixedSocketFactory(InputStream input) {
      this.input = input;
    }

    @Override
    public Socket createSocket() {
      return new Socket() {
        private final OutputStream output = new ByteArrayOutputStream();

        @Override
        public boolean isConnected() {
          return true;
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) {
        }

        @Override
        public void setTcpNoDelay(boolean on) {
        }

        @Override
        public int getSendBufferSize() {
          return 8192;
        }

        @Override
        public InputStream getInputStream() {
          return input;
        }

        @Override
        public OutputStream getOutputStream() {
          return output;
        }

        @Override
        public void close() {
        }
      };
    }

    @Override
    public Socket createSocket(String host, int port) {
      return createSocket();
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
      return createSocket();
    }

    @Override
    public Socket createSocket(InetAddress host, int port) {
      return createSocket();
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
        int localPort) {
      return createSocket();
    }
  }
}
