/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.impl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.Driver;
import org.postgresql.PGProperty;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A connection attempt that exceeds {@code loginTimeout} is recognized even when a test wraps it
 * in an assertion error, and the thread dump printed for it shows the abandoned connection thread
 * inside the driver.
 *
 * <p>The server is a local socket that accepts the connection and never answers, so the
 * connection thread waits for the reply to the startup packet until the test closes the socket.
 */
class LoginTimeoutThreadDumpTest {
  private ServerSocket silentServer;
  private final List<Socket> accepted = new CopyOnWriteArrayList<>();

  @BeforeEach
  void startSilentServer() throws IOException {
    silentServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    Thread acceptor = new Thread(() -> {
      try {
        while (true) {
          accepted.add(silentServer.accept());
        }
      } catch (IOException e) {
        // The test closed the server socket
      }
    }, "LoginTimeoutThreadDumpTest silent server");
    acceptor.setDaemon(true);
    acceptor.start();
  }

  @AfterEach
  void stopSilentServer() throws IOException {
    silentServer.close();
    // The abandoned connection thread gets end of stream and exits
    for (Socket socket : accepted) {
      socket.close();
    }
  }

  private static SQLException connect(int port, String loginTimeout) {
    Properties props = new Properties();
    PGProperty.LOGIN_TIMEOUT.set(props, loginTimeout);
    PGProperty.SSL_MODE.set(props, "disable");
    PGProperty.GSS_ENC_MODE.set(props, "disable");
    PGProperty.USER.set(props, "test");
    String url = "jdbc:postgresql://127.0.0.1:" + port + "/test";
    return assertThrows(SQLException.class, () -> new Driver().connect(url, props),
        "connect(" + url + ", loginTimeout=" + loginTimeout + ")");
  }

  @Test
  void aLoginTimeoutSuppressedByAnAssertionErrorIsFound() {
    SQLException timeout = connect(silentServer.getLocalPort(), "1");
    AssertionError failure = new AssertionError("SSL validation failed");
    failure.addSuppressed(timeout);

    assertSame(timeout, LoginTimeoutThreadDump.findLoginTimeout(failure));
  }

  @Test
  void aLoginTimeoutThatCausedAnAssertionErrorIsFound() {
    SQLException timeout = connect(silentServer.getLocalPort(), "1");
    // SslTest.run attaches the connection failure to its assertion error this way
    AssertionError failure = new AssertionError("SSL validation failed");
    failure.initCause(timeout);

    assertSame(timeout, LoginTimeoutThreadDump.findLoginTimeout(failure));
  }

  @Test
  void theTimeoutMessageThrownOutsideTheDriverIsNotALoginTimeout() {
    SQLException elsewhere = new PSQLException(GT.tr("Connection attempt timed out."),
        PSQLState.CONNECTION_UNABLE_TO_CONNECT);

    assertNull(LoginTimeoutThreadDump.findLoginTimeout(elsewhere));
  }

  @Test
  void aRefusedConnectionIsNotALoginTimeout() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closedPort = socket.getLocalPort();
    }
    // Driver.ConnectTask rethrows this failure from the same method that throws the timeout
    SQLException refused = connect(closedPort, "10");

    assertNull(LoginTimeoutThreadDump.findLoginTimeout(refused));
  }

  @Test
  void theDumpShowsTheConnectionThreadWaitingInTheDriver() {
    SQLException timeout = connect(silentServer.getLocalPort(), "1");
    assertNotNull(LoginTimeoutThreadDump.findLoginTimeout(timeout),
        () -> "not recognized as a login timeout: " + timeout);

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    LoginTimeoutThreadDump.print("silent server", new PrintStream(buffer, true));
    String dump = new String(buffer.toByteArray(), StandardCharsets.UTF_8);

    // Other tests running at the same time may have connection threads of their own, some of
    // them just starting or finishing with no frames, so look for the one waiting in the driver
    String header = "\n\"" + LoginTimeoutThreadDump.CONNECTION_THREAD_NAME + "\" #";
    boolean waitingInDriver = false;
    for (int thread = dump.indexOf(header); thread >= 0 && !waitingInDriver;
        thread = dump.indexOf(header, thread + 1)) {
      String connectionThread = dump.substring(thread, dump.indexOf("\n\n", thread + 1));
      waitingInDriver = connectionThread.contains("org.postgresql.core.v3.ConnectionFactoryImpl");
    }
    assertTrue(waitingInDriver,
        () -> "no connection thread in the dump has a ConnectionFactoryImpl frame:\n" + dump);
  }
}
