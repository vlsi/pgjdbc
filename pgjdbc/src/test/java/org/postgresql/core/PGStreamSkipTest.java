/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
 * <p>A short discard leaves the connection off a message boundary, which a later read reports as
 * a protocol error, and a discard that never finishes hangs the connection. Since
 * {@link VisibleBufferedInputStream#skip(long)} returns the count it discarded rather than the
 * count asked for, {@link PGStream#skip(int)} has to tell a short count apart from end of stream.
 * It keeps discarding after a short count, and throws {@link EOFException} at end of stream
 * rather than waiting for bytes that will not arrive.</p>
 *
 * <p>{@link VisibleBufferedInputStream#skip(long)} discards through its own buffer and never
 * calls {@link InputStream#skip(long)} on the stream below, so a discard does not depend on how a
 * stream supplied through the {@code socketFactory} connection property implements {@code skip}.
 * {@link FixedSocketFactory} stands in for such a factory and {@link NoSkipStream} for the stream
 * its socket exposes, so a discard that reaches an implementation the driver did not write fails
 * the test.</p>
 */
// A discard is a loop, not a single call, so a wrong loop condition spins instead of returning.
// The timeout turns that spin into a failure of the code under test instead of a stalled build,
// and it needs the separate thread mode to do that: the default mode checks the clock only after
// the test method returns, and a spinning method never returns
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PGStreamSkipTest {
  /**
   * Payload the stream serves. A byte cannot encode an offset in a payload this long, so the
   * pattern separates the offsets a wrong discard is most likely to land on: multiplying by seven
   * separates neighbors, and adding {@code i >> 8} separates offsets a multiple of 256 apart. The
   * closest offsets it leaves equal are 73 apart, so the test also counts the bytes left after
   * the discard.
   */
  private static final byte[] DATA = new byte[50000];

  static {
    for (int i = 0; i < DATA.length; i++) {
      DATA[i] = (byte) (i * 7 + 1 + (i >> 8));
    }
  }

  @Test
  void skipDiscardsExactlyTheRequestedBytes() throws Exception {
    // The read buffer under PGStream is 8192 bytes. The sizes straddle it, so both a discard
    // served from one fill and a discard that needs several refills are exercised. DATA.length
    // covers a discard of the whole payload, where there is no byte after the skip
    for (int size : new int[]{0, 1, 100, 8192, 20000, DATA.length - 1, DATA.length}) {
      NoSkipStream source = new NoSkipStream(new ByteArrayInputStream(DATA));
      try (PGStream stream = openStream(source)) {
        String label = "skip=" + size;
        stream.skip(size);
        int consumed = size;
        if (size < DATA.length) {
          assertEquals(DATA[size] & 0xFF, stream.receiveChar(), label + ": byte after the skip");
          consumed++;
        }
        // The byte after the skip leaves some wrong offsets undetected. The count of bytes
        // left catches them
        assertEquals(DATA.length - consumed, drain(stream), label + ": bytes left to read");
      }
    }
  }

  @Test
  void skipReportsAStreamThatEndsEarly() throws Exception {
    NoSkipStream source = new NoSkipStream(new ByteArrayInputStream(DATA));
    try (PGStream stream = openStream(source)) {
      assertThrows(EOFException.class, () -> stream.skip(DATA.length + 1),
          "skip must report end of stream when the payload ends before the requested count");
    }
  }

  /** Reads to end of stream and returns how many bytes were still there. */
  private static int drain(PGStream stream) throws IOException {
    int read = 0;
    while (true) {
      try {
        stream.receiveChar();
      } catch (EOFException e) {
        return read;
      }
      read++;
    }
  }

  /**
   * Opens a {@link PGStream} that reads from {@code source}, with no server behind it. The 8192
   * argument sizes the send buffer; it does not size the buffer the driver reads through when it
   * skips, which is a separate fixed 8192 set up when the socket is attached.
   */
  private static PGStream openStream(InputStream source) throws IOException {
    return new PGStream(new FixedSocketFactory(source), new HostSpec("localhost", 5432), 0, 8192);
  }

  /**
   * Fails the test when the driver calls {@code skip} on the wrapped stream.
   *
   * <p>{@link VisibleBufferedInputStream#skip(long)} discards through its own buffer, so a discard
   * that arrives here bypassed it.</p>
   */
  static final class NoSkipStream extends FilterInputStream {
    NoSkipStream(InputStream in) {
      super(in);
    }

    @Override
    public long skip(long n) {
      throw new AssertionError("skip must not reach the wrapped stream, since"
          + " VisibleBufferedInputStream discards through its own buffer");
    }
  }

  /**
   * Supplies a socket whose input is the given stream, so no server is involved.
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
