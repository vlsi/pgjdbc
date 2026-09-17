/*
 * Copyright (c) 2008, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.MessageProp;

import java.io.IOException;
import java.io.InputStream;

/**
 * Decrypts the packets of a GSS-encrypted connection.
 *
 * <p>Each packet is a four-byte big-endian length followed by that many encrypted bytes, and is
 * unwrapped whole, so a read returns bytes only once a complete packet has arrived. A read stops
 * once {@code available()} on the wrapped stream reports nothing more, and returns the bytes it
 * has copied so far, or {@code 0} when it has copied none.</p>
 */
public class GSSInputStream extends InputStream {
  private final GSSContext gssContext;
  private final MessageProp messageProp;
  private final InputStream wrapped;
  // See https://www.postgresql.org/docs/current/protocol-flow.html#PROTOCOL-FLOW-GSSAPI
  // The server can be expected to not send encrypted packets of larger than 16kB to the client
  private static final int MAX_PACKET_SIZE = 16 * 1024;
  // PQ_GSS_MAX_PACKET_SIZE counts the uint32 length word.
  private static final int MAX_PAYLOAD_SIZE = MAX_PACKET_SIZE - 4;
  private static final Runnable NO_OP = new Runnable() {
    @Override
    public void run() {
    }
  };
  private final byte[] encrypted = new byte[MAX_PACKET_SIZE];
  private int encryptedPos;
  private int encryptedLength;

  private byte @Nullable [] unencrypted;
  private int unencryptedPos;

  private final byte[] int4Buf = new byte[4];
  private int lenPos;

  private final byte[] int1Buf = new byte[1];

  private final Runnable onProtocolViolation;

  public GSSInputStream(InputStream wrapped, GSSContext gssContext, MessageProp messageProp) {
    this(wrapped, gssContext, messageProp, NO_OP);
  }

  /**
   * @param onProtocolViolation run when a declared packet length is refused, before the
   *     {@link IOException} is thrown
   */
  public GSSInputStream(InputStream wrapped, GSSContext gssContext, MessageProp messageProp,
      Runnable onProtocolViolation) {
    this.wrapped = wrapped;
    this.gssContext = gssContext;
    this.messageProp = messageProp;
    this.onProtocolViolation = onProtocolViolation;
  }

  @Override
  public int read() throws IOException {
    int res = 0;
    while (res == 0) {
      res = read(int1Buf);
    }
    return res == -1 ? -1 : int1Buf[0] & 0xFF;
  }

  @Override
  public int read(byte[] buffer, int pos, int len) throws IOException {
    int n = 0;
    // Server makes 16KiB frames, so we attempt several reads from the underlying stream
    // so we don't have to store the unencrypted buffer across GSSInputStream.read calls
    while (true) {
      // 1. Reading length from the wrapped stream
      if (lenPos < 4) {
        int res = readLength();
        if (res <= 0) {
          // Did not read "message length" fully, so we can't read encrypted message yet
          return n == 0 ? res : n;
        }
      }

      // 2. Reading encrypted message from the wrapped stream
      if (encryptedPos < encryptedLength) {
        int res = readEncryptedBytesAndUnwrap();
        if (res <= 0) {
          // Did not read encrypted message fully, so we can't deliver decrypted data yet
          return n == 0 ? res : n;
        }
      }

      // 3. Reading unencrypted message into the user-provided buffer
      byte[] unencrypted = castNonNull(this.unencrypted);
      int copyLength = Math.min(len - n, unencrypted.length - unencryptedPos);
      System.arraycopy(unencrypted, unencryptedPos, buffer, pos + n, copyLength);
      unencryptedPos += copyLength;
      n += copyLength;
      if (unencryptedPos == unencrypted.length) {
        // Start reading the new message on the next read
        lenPos = 0;
        encryptedPos = 0;
        this.unencrypted = null;
      }
      if (n >= len || wrapped.available() <= 0) {
        return n;
      }
    }
  }

  /**
   * Reads the four-byte length of the next packet.
   *
   * @return -1 if end of stream is reached, 0 if the length is not fully read and the wrapped
   *     stream has no more bytes available, and 1 if the length is fully read
   * @throws IOException if the read fails, or if the declared length is not between 1 and
   *     {@link #MAX_PAYLOAD_SIZE}, in which case {@link #onProtocolViolation} runs first
   */
  private int readLength() throws IOException {
    while (true) {
      int res = wrapped.read(int4Buf, lenPos, 4 - lenPos);
      if (res == -1) {
        return -1;
      }
      lenPos += res;
      if (lenPos == 4) {
        break;
      }
      if (wrapped.available() <= 0) {
        // Did not read "message length" fully, and there's no more bytes available, so stop trying
        return 0;
      }
    }
    encryptedLength = ByteConverter.int4(int4Buf, 0);
    // A length of at most MAX_PAYLOAD_SIZE always fits the encrypted array.
    if (encryptedLength < 1 || encryptedLength > MAX_PAYLOAD_SIZE) {
      onProtocolViolation.run();
      throw new IOException(GT.tr("Backend declared a GSS packet of {0} bytes, the maximum is {1}.",
          String.valueOf(encryptedLength), String.valueOf(MAX_PAYLOAD_SIZE)));
    }
    return 1;
  }

  /**
   * Reads the encrypted message, and unwraps it.
   *
   * @return -1 of end of stream reached, 0 if the message is not fully read yet, and 1 if length is
   *     fully read
   * @throws IOException if read fails
   */
  private int readEncryptedBytesAndUnwrap() throws IOException {
    while (true) {
      int res = wrapped.read(encrypted, encryptedPos, encryptedLength - encryptedPos);
      if (res == -1) {
        // Should we raise something like "incomplete GSS message due to end of input stream"?
        return -1;
      }
      encryptedPos += res;
      if (encryptedPos == encryptedLength) {
        break;
      }
      if (wrapped.available() <= 0) {
        // The encrypted message is not yet ready, so we can't read user data yet
        return 0;
      }
    }
    try {
      this.unencrypted = gssContext.unwrap(encrypted, 0, encryptedLength, messageProp);
    } catch (GSSException e) {
      throw new IOException(e);
    }
    unencryptedPos = 0;
    return 1;
  }
}
