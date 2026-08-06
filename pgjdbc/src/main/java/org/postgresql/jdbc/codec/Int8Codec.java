/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingByteArrayOutputStream;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveBinaryDecoder;
import org.postgresql.api.codec.PrimitiveBinaryEncoder;
import org.postgresql.api.codec.PrimitiveTextDecoder;
import org.postgresql.api.codec.PrimitiveTextEncoder;
import org.postgresql.api.codec.PrimitiveTextSink;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Encoding;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.NumberParser;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL int8 (BIGINT) type.
 */
public final class Int8Codec implements PrimitiveBinaryEncoder, PrimitiveBinaryDecoder,
    PrimitiveTextEncoder, PrimitiveTextDecoder, ArrayElementCodec {

  public static final Int8Codec INSTANCE = new Int8Codec();

  private Int8Codec() {
    // Singleton
  }

  @Override
  public boolean mayRequireQuoting(TypeDescriptor type, CodecContext ctx) {
    // Output is digits with an optional leading sign — never needs composite/array quoting.
    return false;
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Long.class;
  }

  @Override
  public ArrayLeafCodec arrayLeaf() {
    return Int8ArrayLeafCodec.INSTANCE;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length != 8) {
      throw Exceptions.invalidBinaryLength("int8", length);
    }
    return ByteConverter.int8(data, offset);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    long v = toLong(value);
    byte[] result = new byte[8];
    ByteConverter.int8(result, 0, v);
    return result;
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingByteArrayOutputStream out) throws SQLException, IOException {
    out.writeInt64(toLong(value));
  }

  @Override
  public void encodeInt(int value, TypeDescriptor type, CodecContext ctx, BackpatchingByteArrayOutputStream out)
      throws SQLException, IOException {
    out.writeInt64(value);
  }

  @Override
  public void encodeLong(long value, TypeDescriptor type, CodecContext ctx, BackpatchingByteArrayOutputStream out)
      throws SQLException, IOException {
    out.writeInt64(value);
  }

  @Override
  public @Nullable Object decodeText(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsLong(data, type, ctx);
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return String.valueOf(toLong(value));
  }

  @Override
  public void encodeText(Object value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    PrimitiveTextSink.appendLong(out, toLong(value));
  }

  @Override
  public void encodeInt(int value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    PrimitiveTextSink.appendInt(out, value);
  }

  @Override
  public void encodeLong(long value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    PrimitiveTextSink.appendLong(out, value);
  }

  @Override
  public int decodeAsInt(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return Exceptions.checkIntRange(decodeAsLong(data, offset, length, type, ctx), "int");
  }

  @Override
  public int decodeAsInt(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return Exceptions.checkIntRange(decodeAsLong(data, type, ctx), "int");
  }

  @Override
  public long decodeAsLong(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (length != 8) {
      throw Exceptions.invalidBinaryLength("int8", length);
    }
    return ByteConverter.int8(data, offset);
  }

  @Override
  public long decodeAsLong(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    try {
      return NumberParser.getFastLong(data, 0, data.length(), Long.MIN_VALUE, Long.MAX_VALUE);
    } catch (NumberFormatException fast) {
      // The fast path rejects a leading '+', whitespace, a non-ASCII digit, or an out-of-range
      // value; fall back to the String parser, which owns the parse and the error message.
      // Long.parseLong would accept a non-ASCII digit, so requireAsciiLiteral screens for one here
      // rather than on the fast path, where a well-formed value would pay for the scan.
      String text = data.toString();
      try {
        NumberDecoders.requireAsciiLiteral(text);
        return Long.parseLong(text.trim());
      } catch (NumberFormatException e) {
        throw Exceptions.cannotConvertValue("long", text, e);
      }
    }
  }

  @Override
  public long decodeTextBytesAsLong(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (Encoding.hasAsciiNumbers(ctx.getCharset())) {
      try {
        return NumberParser.getFastLong(data, Long.MIN_VALUE, Long.MAX_VALUE);
      } catch (NumberFormatException ignored) {
        // The String parser re-reports a malformed or out-of-range literal, with its own message.
      }
    }
    return decodeAsLong(new String(data, ctx.getCharset()), type, ctx);
  }

  @Override
  public int decodeTextBytesAsInt(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return Exceptions.checkIntRange(decodeTextBytesAsLong(data, type, ctx), "int");
  }

  @Override
  public double decodeAsDouble(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return decodeAsLong(data, offset, length, type, ctx);
  }

  @Override
  public double decodeAsDouble(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsLong(data, type, ctx);
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return BigDecimal.valueOf(decodeAsLong(data, offset, length, type, ctx));
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    long value = decodeAsLong(data, offset, length, type, ctx);
    return decodeLongAs(value, targetClass);
  }

  @Override
  public <T> @Nullable T decodeTextAs(CharSequence data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    long value = decodeAsLong(data, type, ctx);
    return decodeLongAs(value, targetClass);
  }

  // int8's natural getObject type is Long (and the value is already a long); resolve it and Object
  // directly. BigInteger is int8-specific (int2/int4 do not offer it); the rest share NumberDecoders.
  @SuppressWarnings("unchecked")
  private static <T> T decodeLongAs(long value, Class<T> targetClass) throws SQLException {
    if (targetClass == Long.class || targetClass == Object.class) {
      return (T) Long.valueOf(value);
    }
    if (targetClass == java.math.BigInteger.class) {
      return (T) java.math.BigInteger.valueOf(value);
    }
    return NumberDecoders.decodeIntegralAs(value, targetClass, "int8");
  }

  /**
   * Converts a bind value to the {@code long} an {@code int8} encoder writes.
   *
   * @param value a {@link Number}, narrowed through {@link Number#longValue()} with no range or
   *     fraction check; a {@code String} decimal literal, trimmed and required to be ASCII; or a
   *     {@link Boolean}, mapped to 1 or 0
   * @throws SQLException if {@code value} is of any other class, or the string is not a {@code long}
   */
  static long toLong(Object value) throws SQLException {
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    if (value instanceof String) {
      try {
        NumberDecoders.requireAsciiLiteral((String) value);
        return Long.parseLong(((String) value).trim());
      } catch (NumberFormatException e) {
        throw Exceptions.cannotConvertValue("long", value, e);
      }
    }
    if (value instanceof Boolean) {
      return (Boolean) value ? 1L : 0L;
    }
    throw Exceptions.cannotEncode(value, "int8");
  }
}
