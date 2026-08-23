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
 * <p>Discarding is where the driver relies on {@link InputStream#skip(long)}, which is allowed to
 * skip nothing and still have more to give. A stream that has ended also skips nothing, and it
 * says so forever. Telling the two apart is the whole job: read the end as the end, and treat a
 * stream that is merely being unhelpful as one that still owes bytes. Getting it wrong either
 * hangs the connection or breaks a usable one, and getting the count wrong leaves the connection
 * off a message boundary, which surfaces later as a protocol error somewhere else.</p>
 *
 * <p>The streams below stand in for what a {@code socketFactory} may hand the driver, which is
 * where an implementation the driver did not write can reach it.</p>
 */
// A stream that never makes progress used to spin here, so cap the wait: a hang is a failure
// of the thing under test, and it should read as one rather than stalling the build. The
// separate thread is what makes that work, since the default mode only checks the clock once
// the test method returns, which a spinning one never does
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PGStreamSkipTest {
  /**
   * Payload the stream serves. Every byte differs from its neighbours, so a skip that lands at the
   * wrong offset is visible in the byte read after it.
   */
  private static final byte[] DATA = new byte[256];

  static {
    for (int i = 0; i < DATA.length; i++) {
      DATA[i] = (byte) (i * 7 + 1);
    }
  }

  /**
   * How many bytes a wrapped stream agrees to skip per call.
   */
  enum SkipStyle {
    /** Skips everything asked of it, the ordinary case. */
    HONEST,
    /** Skips nothing, ever, which the contract permits. */
    REFUSES,
    /** Skips a single byte per call, so progress is real but slow. */
    ONE_AT_A_TIME
  }

  @Test
  void skipDiscardsExactlyTheRequestedBytes() throws Exception {
    // The buffer under PGStream is 8192 bytes, so a request below the payload is served from it
    // once it is primed, and one above it has to reach the wrapped stream
    for (SkipStyle style : SkipStyle.values()) {
      for (int size : new int[]{0, 1, 100, DATA.length - 1}) {
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
   * A stream that refuses to skip must not degrade to one read per byte: the read that breaks the
   * refusal primes the buffer, and the next skip drains it wholesale.
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

  private static PGStream openStream(InputStream source) throws IOException {
    return new PGStream(new FixedSocketFactory(source), new HostSpec("localhost", 5432), 0, 8192);
  }

  /**
   * Counts what the driver asked of the stream, and answers skip as the style dictates.
   *
   * <p>Refuses to play along with a caller that ignores a zero from {@link #skip(long)}: after
   * {@link #ZERO_SKIP_LIMIT} of them with nothing read in between, it throws instead of letting
   * the caller spin. That turns the defect this test guards into an immediate failure that names
   * itself, rather than one the surrounding timeout has to catch.</p>
   */
  static final class CountingStream extends FilterInputStream {
    /**
     * Consecutive zero-returning skips that count as a caller making no progress. Reading resets
     * the count, since that is how a caller reacts to a skip that skipped nothing.
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
   * Hands the driver a socket whose input is the given stream, so no server is involved.
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
