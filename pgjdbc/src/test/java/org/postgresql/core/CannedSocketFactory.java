/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;

import javax.net.SocketFactory;

/**
 * Creates sockets that read a canned byte script and record everything written to them.
 *
 * <p>The script is prepared before the test runs and does not depend on what the driver writes, so
 * a test can drive a reader with no server behind the socket. A read past the end of the script
 * reports end of stream rather than blocking.</p>
 */
public class CannedSocketFactory extends SocketFactory {
  /** Bytes each socket this factory creates replays as its input, from the beginning. */
  private final byte[] script;
  private CannedSocket socket;

  public CannedSocketFactory(byte[] script) {
    this.script = script;
    this.socket = new CannedSocket(script);
  }

  /**
   * Bytes the driver has written to the socket created most recently. {@link #createSocket()}
   * replaces that socket and drops what was written to the one before it.
   */
  public byte[] getWritten() {
    return socket.written.toByteArray();
  }

  @Override
  public Socket createSocket() {
    socket = new CannedSocket(script);
    return socket;
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
  public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort) {
    return createSocket();
  }

  /**
   * Socket that reads the script and records what is written to it, with no operating-system
   * socket behind it.
   *
   * <p>It reports itself connected, so the driver connects nothing and resolves no address. A
   * socket option is recorded or ignored rather than applied.</p>
   */
  private static class CannedSocket extends Socket {
    private final InputStream in;
    private final ByteArrayOutputStream written = new ByteArrayOutputStream();
    private int soTimeout;

    CannedSocket(byte[] script) {
      this.in = new ByteArrayInputStream(script);
    }

    @Override
    public boolean isConnected() {
      return true;
    }

    @Override
    public InputStream getInputStream() {
      return in;
    }

    @Override
    public OutputStream getOutputStream() {
      return written;
    }

    @Override
    public void setTcpNoDelay(boolean on) {
    }

    @Override
    public int getSendBufferSize() {
      return 8192;
    }

    @Override
    public void setSoTimeout(int timeout) {
      this.soTimeout = timeout;
    }

    @Override
    public int getSoTimeout() {
      return soTimeout;
    }

    @Override
    public void setSoLinger(boolean on, int linger) {
      // Socket.setSoLinger creates a real descriptor to set the option on.
    }

    /**
     * Leaves the socket reported open, so {@link Socket#isClosed()} returns {@code false} even
     * after the driver has closed the stream.
     */
    @Override
    public void close() {
    }
  }
}
