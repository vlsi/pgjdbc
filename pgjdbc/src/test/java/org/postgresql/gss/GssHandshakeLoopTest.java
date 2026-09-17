/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.CannedSocketFactory;
import org.postgresql.core.PGStream;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.ietf.jgss.GSSContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Both GSS handshakes stop with a protocol violation when the context never establishes.
 *
 * <p>A zero length GSS token is a valid continuation, so a backend that returns one for every
 * token it receives never ends the handshake. The driver stops after
 * {@link PGStream#MAX_AUTH_ROUND_TRIPS} rounds and marks the stream broken, and
 * {@link GssEncAction} refuses a token whose declared length is over its limit.</p>
 *
 * <p>A real {@code GSSContext} needs a Kerberos realm, so each test passes a stub context to the
 * package-private {@code negotiate} method.</p>
 */
@Isolated("Uses Locale.setDefault")
class GssHandshakeLoopTest {

  private static final int MAX_ROUNDS = PGStream.MAX_AUTH_ROUND_TRIPS;

  private static Locale defaultLocale;

  // The assertions match on message text, which GT.tr translates once these strings are
  // localized.
  @BeforeAll
  static void useRootLocale() {
    defaultLocale = Locale.getDefault();
    Locale.setDefault(Locale.ROOT);
  }

  @AfterAll
  static void restoreLocale() {
    Locale.setDefault(defaultLocale);
  }

  /** Returns a context that produces a one byte token and never reports itself established. */
  private static GSSContext neverEstablishedContext() {
    InvocationHandler handler = new InvocationHandler() {
      @Override
      public Object invoke(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
          case "initSecContext":
            return new byte[]{1};
          case "isEstablished":
            return Boolean.FALSE;
          default:
            return null;
        }
      }
    };
    return (GSSContext) Proxy.newProxyInstance(GssHandshakeLoopTest.class.getClassLoader(),
        new Class<?>[]{GSSContext.class}, handler);
  }

  private static PGStream streamOf(byte[] script, CannedSocketFactory[] out) throws IOException {
    CannedSocketFactory factory = new CannedSocketFactory(script);
    out[0] = factory;
    return new PGStream(factory, new HostSpec("localhost", 5432), 0, 8192);
  }

  /**
   * Returns {@code count} AuthenticationGSSContinue messages, each carrying a zero length token.
   */
  private static byte[] continueMessages(int count) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < count; i++) {
      out.write('R');
      out.write(new byte[]{0, 0, 0, 8, 0, 0, 0, 8}, 0, 8);
    }
    return out.toByteArray();
  }

  /**
   * Returns {@code count} zero length tokens, each a four byte length and no payload. The
   * encryption handshake frames tokens that way, with no message type byte.
   */
  private static byte[] rawTokens(int count) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < count; i++) {
      out.write(new byte[]{0, 0, 0, 0}, 0, 4);
    }
    return out.toByteArray();
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void stopsTheAuthenticationHandshakeAtTheRoundCap() throws Exception {
    CannedSocketFactory[] factory = new CannedSocketFactory[1];
    PGStream stream = streamOf(continueMessages(MAX_ROUNDS + 10), factory);
    GssAction action = new GssAction(stream, null, "localhost", "test", "postgres", false, false,
        false);

    Exception e = action.negotiate(neverEstablishedContext());

    assertNotNull(e, "negotiate must report an error once the round limit is reached");
    assertTrue(e.getMessage().contains("round trips"), e.getMessage());
    assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), ((PSQLException) e).getSQLState());
    assertTrue(stream.isBroken());
    // Each round sends a GSSResponse: a type byte, a four byte length, and the one byte token.
    assertEquals(MAX_ROUNDS * 6, factory[0].getWritten().length,
        "the driver must send one token per round and none past the limit");
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void stopsTheEncryptionHandshakeAtTheRoundCap() throws Exception {
    CannedSocketFactory[] factory = new CannedSocketFactory[1];
    PGStream stream = streamOf(rawTokens(MAX_ROUNDS + 10), factory);
    GssEncAction action = new GssEncAction(stream, null, "localhost", "test", "postgres", false,
        false, false);

    Exception e = action.negotiate(neverEstablishedContext());

    assertNotNull(e, "negotiate must report an error once the round limit is reached");
    assertTrue(e.getMessage().contains("round trips"), e.getMessage());
    assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), ((PSQLException) e).getSQLState());
    assertTrue(stream.isBroken());
    // Each round sends a four byte length followed by a one byte token.
    assertEquals(MAX_ROUNDS * 5, factory[0].getWritten().length,
        "the driver must send one token per round and none past the limit");
  }

  /**
   * The four script bytes are a declared token length of 65536. The encryption handshake reads
   * that length raw, and refuses an oversized one before reading any token body.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void rejectsAnOversizedHandshakeToken() throws Exception {
    CannedSocketFactory[] factory = new CannedSocketFactory[1];
    byte[] script = new byte[]{0, 1, 0, 0};
    PGStream stream = streamOf(script, factory);
    GssEncAction action = new GssEncAction(stream, null, "localhost", "test", "postgres", false,
        false, false);

    IOException e = assertThrows(IOException.class,
        () -> action.negotiate(neverEstablishedContext()));

    assertTrue(e.getMessage().contains("GSS token"), e.getMessage());
    assertTrue(stream.isBroken());
  }
}
