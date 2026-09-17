/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.MessageProp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A GSS packet is refused unless its declared length is between 1 and {@link #MAX_PAYLOAD_SIZE}
 * bytes, and the refusal runs the protocol violation callback before the {@link IOException} is
 * thrown.
 *
 * <p>The driver installs {@link GSSInputStream} only on a GSS-encrypted connection. The
 * {@link GSSContext} here is a stub whose unwrap returns the bytes it is given, so the length
 * handling is exercised without a Kerberos realm.</p>
 */
@Isolated("Uses Locale.setDefault")
class GSSInputStreamTest {

  /**
   * Largest declared packet length, in bytes, that {@link GSSInputStream} accepts. PostgreSQL's
   * PQ_GSS_MAX_PACKET_SIZE counts the four-byte length word, so the payload maximum is four bytes
   * smaller.
   */
  private static final int MAX_PAYLOAD_SIZE = 16 * 1024 - 4;

  // The assertions match on English message text. GT.tr returns the catalog entry for the default
  // locale instead, once these messages have one.
  private static Locale defaultLocale;

  @BeforeAll
  static void useRootLocale() {
    defaultLocale = Locale.getDefault();
    Locale.setDefault(Locale.ROOT);
  }

  @AfterAll
  static void restoreLocale() {
    Locale.setDefault(defaultLocale);
  }

  /**
   * Returns a {@link GSSContext} whose unwrap returns the range of bytes it is given.
   */
  private static GSSContext echoContext() {
    InvocationHandler handler = new InvocationHandler() {
      @Override
      public Object invoke(Object proxy, Method method, Object[] args) {
        if ("unwrap".equals(method.getName())) {
          byte[] buf = (byte[]) args[0];
          int off = (Integer) args[1];
          int len = (Integer) args[2];
          return Arrays.copyOfRange(buf, off, off + len);
        }
        return null;
      }
    };
    return (GSSContext) Proxy.newProxyInstance(GSSInputStreamTest.class.getClassLoader(),
        new Class<?>[]{GSSContext.class}, handler);
  }

  /**
   * Returns a packet whose four-byte header declares {@code declaredLength}, followed by
   * {@code payloadBytes} bytes of payload. The two can differ.
   */
  private static byte[] frame(int declaredLength, int payloadBytes) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(declaredLength >>> 24);
    out.write(declaredLength >>> 16);
    out.write(declaredLength >>> 8);
    out.write(declaredLength);
    for (int i = 0; i < payloadBytes; i++) {
      out.write('x');
    }
    return out.toByteArray();
  }

  private static GSSInputStream streamOf(byte[] bytes, AtomicBoolean violated) {
    return new GSSInputStream(new ByteArrayInputStream(bytes), echoContext(),
        new MessageProp(0, true),
        new Runnable() {
          @Override
          public void run() {
            violated.set(true);
          }
        });
  }

  @Test
  void rejectsAPacketAboveThePayloadMaximum() {
    AtomicBoolean violated = new AtomicBoolean();
    GSSInputStream in = streamOf(frame(MAX_PAYLOAD_SIZE + 1, 0), violated);

    IOException e = assertThrows(IOException.class, () -> in.read(new byte[16], 0, 16));

    assertTrue(e.getMessage().contains("GSS packet"), e.getMessage());
    assertTrue(violated.get(), "the protocol violation callback must run when a packet is refused");
  }

  @Test
  void rejectsAZeroLengthPacket() {
    AtomicBoolean violated = new AtomicBoolean();
    GSSInputStream in = streamOf(frame(0, 0), violated);

    assertThrows(IOException.class, () -> in.read(new byte[16], 0, 16));

    assertTrue(violated.get());
  }

  /** The frame carries its whole payload, so the read reaches the unwrap and returns bytes. */
  @Test
  void acceptsAPacketAtThePayloadMaximum() throws IOException {
    AtomicBoolean violated = new AtomicBoolean();
    GSSInputStream in = streamOf(frame(MAX_PAYLOAD_SIZE, MAX_PAYLOAD_SIZE), violated);

    byte[] buffer = new byte[16];
    int read = in.read(buffer, 0, buffer.length);

    assertEquals(buffer.length, read);
    assertEquals('x', buffer[0]);
    assertFalse(violated.get());
  }
}
