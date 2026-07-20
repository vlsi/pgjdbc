/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Encoding;
import org.postgresql.util.HStoreConverter;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;

/**
 * Codec for PostgreSQL hstore type.
 *
 * <p>hstore is a key/value store within a single PostgreSQL value. It stores
 * sets of key/value pairs, where both keys and values are text strings.</p>
 *
 * <p>Text format: "key1"=>"value1", "key2"=>"value2", "nullkey"=>NULL</p>
 *
 * <p>Binary format: int32 count, followed by pairs of (int32 keyLen, key bytes,
 * int32 valLen, val bytes) where valLen=-1 indicates NULL value.</p>
 */
public final class HstoreCodec implements BinaryCodec, TextCodec {

  public static final HstoreCodec INSTANCE = new HstoreCodec();

  private HstoreCodec() {
    // Singleton
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Map.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] buf, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length == 0) {
      return null;
    }
    // HStoreConverter.fromBytes reads a whole array; copy only for a genuine sub-slice.
    byte[] data = offset == 0 && length == buf.length ? buf : Arrays.copyOfRange(buf, offset, offset + length);
    return HStoreConverter.fromBytes(data, Encoding.fromCharset(ctx.getCharset()));
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (value instanceof Map) {
      return HStoreConverter.toBytes((Map<?, ?>) value, Encoding.fromCharset(ctx.getCharset()));
    }
    throw Exceptions.cannotEncodeAs(value, "hstore");
  }

  @Override
  public @Nullable Object decodeText(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String text = data.toString();
    if (text == null || text.isEmpty()) {
      return null;
    }
    return HStoreConverter.fromString(text);
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (value instanceof Map) {
      return HStoreConverter.toString((Map<?, ?>) value);
    }
    throw Exceptions.cannotEncodeAs(value, "hstore");
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    if (targetClass == Map.class || targetClass == Object.class) {
      return (T) decodeBinary(data, offset, length, type, ctx);
    }
    throw Exceptions.cannotDecode("hstore", targetClass.getName());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(CharSequence data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == Map.class || targetClass == Object.class) {
      return (T) decodeText(data, type, ctx);
    }
    throw Exceptions.cannotDecode("hstore", targetClass.getName());
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    Object map = decodeBinary(data, offset, length, type, ctx);
    return map != null ? HStoreConverter.toString((Map<?, ?>) map) : null;
  }

  @Override
  public @Nullable String decodeAsString(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String text = data.toString();
    return text;
  }
}
