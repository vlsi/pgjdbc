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
 * Codec for PostgreSQL xml type.
 *
 * <p>Decodes to a {@code String} in both wire formats; {@code PgResultSet} hands the value
 * out as the {@code PgSQLXML} wrapper.</p>
 *
 * <p>The server's {@code xml_send}/{@code xml_recv} transfer the value as its text
 * representation in the client encoding, so both wire formats are the charset text bytes
 * (via {@link CodecContext#getCharset()}) with no version byte or added declaration. An
 * empty XML value ({@code xmlparse(content '')}) is a valid, non-null value whose wire form
 * is zero bytes, so it decodes to {@code ""} rather than {@code null}; SQL {@code NULL} is
 * filtered before the codec runs.</p>
 */
public final class XmlCodec implements BinaryCodec, TextCodec {

  public static final XmlCodec INSTANCE = new XmlCodec();

  private XmlCodec() {
  }

  @Override
  public Class<?> getDefaultJavaType() {
    // SQLXML matches the legacy ResultSetMetaData contract; PgResultSet.getObject
    // produces the PgSQLXML wrapper via getSQLXML() rather than the codec's
    // decodeText (which still returns a String for codec-level callers).
    return java.sql.SQLXML.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return new String(data, offset, length, ctx.getCharset());
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String str = value.toString();
    return str.getBytes(ctx.getCharset());
  }

  @Override
  public @Nullable String decodeText(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    // The XML text is the value; materialize it, since the borrowed view goes stale on return.
    return data.toString();
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return value.toString();
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return new String(data, offset, length, ctx.getCharset());
  }

  @Override
  public String decodeAsString(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String text = data.toString();
    return text;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    if (targetClass == String.class || targetClass == Object.class) {
      return (T) new String(data, offset, length, ctx.getCharset());
    }
    throw Exceptions.cannotDecode("xml", targetClass.getName());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(CharSequence data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == String.class || targetClass == Object.class) {
      return (T) data.toString();
    }
    throw Exceptions.cannotDecode("xml", targetClass.getName());
  }
}
