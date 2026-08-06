/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.util.PGobject;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL json type.
 *
 * <p>Returns {@link PGobject} from {@code getObject}, consistent with the legacy
 * driver and with master fix #3926. Applications can extract the JSON text via
 * {@link PGobject#getValue()}, or ask for a {@code String} through
 * {@code getObject(i, String.class)} or {@code getString(i)}; every other target
 * class is refused.</p>
 */
public final class JsonCodec implements BinaryCodec, TextCodec {

  public static final JsonCodec INSTANCE = new JsonCodec();

  private JsonCodec() {
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return PGobject.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length == 0) {
      return null;
    }
    return wrap(new String(data, offset, length, StandardCharsets.UTF_8));
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String str = value.toString();
    return str.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public @Nullable Object decodeText(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return wrap(data.toString());
  }

  private static PGobject wrap(String value) throws SQLException {
    PGobject obj = new PGobject();
    obj.setType("json");
    obj.setValue(value);
    return obj;
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return value.toString();
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length == 0) {
      return null;
    }
    return new String(data, offset, length, StandardCharsets.UTF_8);
  }

  @Override
  public String decodeAsString(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return data.toString();
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    if (length == 0) {
      return null;
    }
    String value = new String(data, offset, length, StandardCharsets.UTF_8);
    if (targetClass == String.class) {
      return (T) value;
    }
    if (targetClass == PGobject.class || targetClass == Object.class) {
      return (T) wrap(value);
    }
    throw Exceptions.cannotDecode("json", targetClass.getName());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(CharSequence data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    String text = data.toString();
    if (text == null || text.isEmpty()) {
      return null;
    }
    if (targetClass == String.class) {
      return (T) text;
    }
    if (targetClass == PGobject.class || targetClass == Object.class) {
      return (T) wrap(text);
    }
    throw Exceptions.cannotDecode("json", targetClass.getName());
  }
}
