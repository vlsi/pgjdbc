/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.util.Arrays;

/**
 * Holds a value of an unknown PostgreSQL type as the raw bytes the server sent in binary format.
 *
 * <p>The driver produces one when it reads binary data for a type that has no registered codec.
 * {@link PGobject} keeps the server's text rendering of such a value; this class keeps the wire
 * bytes, so the value can be bound back to the server without a lossy text conversion.</p>
 *
 * <p>Both the type name and the bytes may be absent; a no-argument instance starts with neither.
 * Byte arrays are copied in and out, so a caller may keep and modify the array it passed to
 * {@link #setBytes(byte[])} or got back from {@link #getBytes()}.</p>
 *
 * @since 42.8.0
 */
public class PGUnknownBinary implements Serializable, Cloneable {

  private static final long serialVersionUID = 1L;

  private @Nullable String type;
  private byte @Nullable [] bytes;

  public PGUnknownBinary() {
  }

  public PGUnknownBinary(String type, byte[] bytes) {
    this.type = type;
    this.bytes = bytes != null ? bytes.clone() : null;
  }

  public @Nullable String getType() {
    return type;
  }

  public void setType(@Nullable String type) {
    this.type = type;
  }

  public byte @Nullable [] getBytes() {
    return bytes != null ? bytes.clone() : null;
  }

  public void setBytes(byte @Nullable [] bytes) {
    this.bytes = bytes != null ? bytes.clone() : null;
  }

  /**
   * Renders the bytes as {@code \x} followed by two lowercase hex digits per byte, or as the literal
   * text {@code null} when no bytes are set. The type name does not appear.
   */
  @Override
  public String toString() {
    byte[] data = bytes;
    if (data == null) {
      return "null";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("\\x");
    for (byte b : data) {
      sb.append(String.format("%02x", b & 0xff));
    }
    return sb.toString();
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof PGUnknownBinary)) {
      return false;
    }
    PGUnknownBinary other = (PGUnknownBinary) obj;
    if (type == null ? other.type != null : !type.equals(other.type)) {
      return false;
    }
    return Arrays.equals(bytes, other.bytes);
  }

  @Override
  public int hashCode() {
    int result = type != null ? type.hashCode() : 0;
    result = 31 * result + Arrays.hashCode(bytes);
    return result;
  }

  @Override
  public PGUnknownBinary clone() throws CloneNotSupportedException {
    PGUnknownBinary clone = (PGUnknownBinary) super.clone();
    if (bytes != null) {
      clone.bytes = bytes.clone();
    }
    return clone;
  }
}
