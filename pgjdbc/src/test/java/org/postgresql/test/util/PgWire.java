/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Builders for backend messages in their wire form, for tests that feed a canned byte
 * script to the driver instead of talking to a server.
 */
public final class PgWire {

  private PgWire() {
  }

  /**
   * A message body preceded by a length prefix the test states rather than computes, so a
   * script can declare one length and send another.
   */
  public static byte[] bodyWithLength(int declaredLength, byte[]... body) {
    return concat(int4(declaredLength), concat(body));
  }

  /** A single byte, such as a message type or a ReadyForQuery status. */
  public static byte[] int1(int value) {
    return new byte[]{(byte) value};
  }

  public static byte[] int4(int value) {
    return new byte[]{
        (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value,
    };
  }

  public static byte[] int2(int value) {
    return new byte[]{(byte) (value >>> 8), (byte) value};
  }

  /** A string encoded as UTF-8, with nothing to terminate it. */
  public static byte[] text(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  /** A NUL-terminated string, encoded as UTF-8. */
  public static byte[] cstring(String value) {
    return concat(text(value), int1(0));
  }

  public static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.write(part, 0, part.length);
    }
    return out.toByteArray();
  }

  /** A byte array of {@code length} bytes with arbitrary but reproducible content. */
  public static byte[] filler(int length) {
    byte[] bytes = new byte[length];
    for (int i = 0; i < length; i++) {
      bytes[i] = (byte) i;
    }
    return bytes;
  }
}
