/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;

/**
 * Codec for PostgreSQL enum types.
 *
 * <p>Decoding yields the label as a {@code String}; a target class other than {@code String} or
 * {@code Object} is refused. Encoding takes a Java enum as well as a {@code String}, but
 * {@code setObject} cannot infer an SQL type for a Java enum, so a JDBC caller converts to the
 * label {@code String} first. Neither direction maps a PostgreSQL enum onto a particular Java enum
 * type.</p>
 *
 * <p>One instance serves every enum type; the codec registry resolves it from
 * {@code typtype='e'}.</p>
 */
public final class EnumCodec implements BinaryCodec, TextCodec {

  public static final EnumCodec INSTANCE = new EnumCodec();

  private EnumCodec() {
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return String.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    // Binary format for enum is the text representation as bytes
    return new String(data, offset, length, ctx.getCharset());
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String stringValue = toEnumString(value);
    return stringValue.getBytes(ctx.getCharset());
  }

  @Override
  public @Nullable String decodeText(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    // The label is the value; materialize it, since the borrowed view goes stale on return.
    return data.toString();
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return toEnumString(value);
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return new String(data, offset, length, ctx.getCharset());
  }

  @Override
  public @Nullable String decodeAsString(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String text = data.toString();
    return text;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    if (targetClass == String.class || targetClass == Object.class) {
      return (T) decodeAsString(data, offset, length, type, ctx);
    }
    throw Exceptions.cannotDecode("enum", targetClass.getName());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(CharSequence data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == String.class || targetClass == Object.class) {
      return (T) data.toString();
    }
    throw Exceptions.cannotDecode("enum", targetClass.getName());
  }

  /**
   * Returns the label to send for a bound value: a {@code String} as itself, a Java enum as
   * {@link Enum#name()}, and any other object as its {@code toString()}.
   */
  private static String toEnumString(Object value) throws SQLException {
    if (value instanceof String) {
      return (String) value;
    }
    if (value instanceof Enum) {
      // name() is the declared constant; toString() is overridable and need not match a label.
      return ((Enum<?>) value).name();
    }
    return value.toString();
  }
}
