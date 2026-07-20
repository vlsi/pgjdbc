/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;

/**
 * Static dispatch for the primitive decode accessors (the read-side counterpart of
 * {@link PrimitiveTextSink}'s and {@link BackpatchingByteArrayOutputStream}'s typed writers).
 *
 * <p>The primitive accessors ({@code decodeAsInt} and friends) live on the opt-in
 * {@link PrimitiveBinaryDecoder} / {@link PrimitiveTextDecoder} capabilities rather than on the base
 * {@link BinaryCodec} / {@link TextCodec}. A caller that has only a base-typed codec reference routes
 * through the {@code asInt}/{@code asLong}/... helpers here: they call the native primitive method
 * when the codec opts in, and otherwise box through {@link BinaryCodec#decodeBinary} /
 * {@link TextCodec#decodeText} and unbox — the same fallback the base interfaces used to inline. So a
 * codec that cannot decode a primitive (a range, a composite, a timestamp) simply does not implement
 * the capability, and the fallback raises the standard "cannot convert" error.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public final class PrimitiveDecoders {

  private PrimitiveDecoders() {
  }

  // ===========================================================================
  // Binary
  // ===========================================================================

  // ---------------------------------------------------------------------------
  // Binary, slice form -- decode a value in place off a larger buffer. A wrapping
  // codec (a domain over its base type) forwards the slice straight through.
  // ---------------------------------------------------------------------------

  /**
   * Decodes binary data as an integer. If the provided codec implements {@code PrimitiveBinaryDecoder},
   * it uses its native path for decoding. Otherwise, it converts the decoded binary result into an integer.
   *
   * @param codec the binary codec to be used for decoding
   * @param data the binary data to decode
   * @param offset the starting position in the data array
   * @param length the number of bytes to decode
   * @param type the type descriptor providing contextual information about the target type
   * @param ctx the codec context containing additional decoding configurations
   * @return the decoded integer value
   * @throws SQLException if an error occurs during decoding
   */
  public static int asInt(BinaryCodec codec, byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (codec instanceof PrimitiveBinaryDecoder) {
      return ((PrimitiveBinaryDecoder) codec).decodeAsInt(data, offset, length, type, ctx);
    }
    return boxToInt(codec.decodeBinary(data, offset, length, type, ctx));
  }

  /**
   * Decodes binary data as a long. If the provided codec implements {@code PrimitiveBinaryDecoder},
   * it uses its native path for decoding. Otherwise, it converts the decoded binary result into a long.
   *
   * @param codec the binary codec to be used for decoding
   * @param data the binary data to decode
   * @param offset the starting position in the data array
   * @param length the number of bytes to decode
   * @param type the type descriptor providing contextual information about the target type
   * @param ctx the codec context containing additional decoding configurations
   * @return the decoded long value
   * @throws SQLException if an error occurs during decoding
   */
  public static long asLong(BinaryCodec codec, byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (codec instanceof PrimitiveBinaryDecoder) {
      return ((PrimitiveBinaryDecoder) codec).decodeAsLong(data, offset, length, type, ctx);
    }
    return boxToLong(codec.decodeBinary(data, offset, length, type, ctx));
  }

  /**
   * Decodes binary data as a float. If the provided codec implements {@code PrimitiveBinaryDecoder},
   * it uses its native decoding path. Otherwise, it converts the decoded binary result into a float.
   *
   * @param codec the binary codec to be used for decoding
   * @param data the binary data to decode
   * @param offset the starting position in the data array
   * @param length the number of bytes to decode
   * @param type the type descriptor providing contextual information about the target type
   * @param ctx the codec context containing additional decoding configurations
   * @return the decoded float value
   * @throws SQLException if an error occurs during decoding
   */
  public static float asFloat(BinaryCodec codec, byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (codec instanceof PrimitiveBinaryDecoder) {
      return ((PrimitiveBinaryDecoder) codec).decodeAsFloat(data, offset, length, type, ctx);
    }
    return boxToFloat(codec.decodeBinary(data, offset, length, type, ctx));
  }

  /**
   * Decodes binary data as a double. If the provided codec implements {@code PrimitiveBinaryDecoder},
   * it uses its native decoding path. Otherwise, it converts the decoded binary result into a double.
   *
   * @param codec the binary codec to be used for decoding
   * @param data the binary data to decode
   * @param offset the starting position in the data array
   * @param length the number of bytes to decode
   * @param type the type descriptor providing contextual information about the target type
   * @param ctx the codec context containing additional decoding configurations
   * @return the decoded double value
   * @throws SQLException if an error occurs during decoding
   */
  public static double asDouble(BinaryCodec codec, byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (codec instanceof PrimitiveBinaryDecoder) {
      return ((PrimitiveBinaryDecoder) codec).decodeAsDouble(data, offset, length, type, ctx);
    }
    return boxToDouble(codec.decodeBinary(data, offset, length, type, ctx));
  }

  /**
   * Decodes binary data as a boolean. If the provided codec implements {@code PrimitiveBinaryDecoder},
   * it uses its native decoding path. Otherwise, it converts the decoded binary result into a boolean.
   *
   * @param codec the binary codec to be used for decoding
   * @param data the binary data to decode
   * @param offset the starting position in the data array
   * @param length the number of bytes to decode
   * @param type the type descriptor providing contextual information about the target type
   * @param ctx the codec context containing additional decoding configurations
   * @return the decoded boolean value
   * @throws SQLException if an error occurs during decoding
   */
  public static boolean asBoolean(BinaryCodec codec, byte[] data, int offset, int length,
      TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (codec instanceof PrimitiveBinaryDecoder) {
      return ((PrimitiveBinaryDecoder) codec).decodeAsBoolean(data, offset, length, type, ctx);
    }
    return BooleanCoercion.castAndCheck(
        codec.decodeBinary(data, offset, length, type, ctx),
        () -> codec.decodeAsString(data, offset, length, type, ctx));
  }

  /**
   * Decodes {@code data[offset, offset + length)} as a {@link BigDecimal} through {@code codec}'s
   * native path when it implements {@link PrimitiveBinaryDecoder}, otherwise by boxing through
   * {@link BinaryCodec#decodeBinary}.
   */
  public static @Nullable BigDecimal asBigDecimal(BinaryCodec codec, byte[] data, int offset,
      int length, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (codec instanceof PrimitiveBinaryDecoder) {
      return ((PrimitiveBinaryDecoder) codec).decodeAsBigDecimal(data, offset, length, type, ctx);
    }
    return boxToBigDecimal(codec.decodeBinary(data, offset, length, type, ctx));
  }

  // ===========================================================================
  // Text
  // ===========================================================================

  /**
   * Decodes {@code data} as an int through {@code codec}'s native path when it implements
   * {@link PrimitiveTextDecoder}, otherwise by boxing through {@link TextCodec#decodeText}.
   */
  public static int asInt(TextCodec codec, CharSequence data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (codec instanceof PrimitiveTextDecoder) {
      return ((PrimitiveTextDecoder) codec).decodeAsInt(data, type, ctx);
    }
    return boxToInt(codec.decodeText(data, type, ctx));
  }

  /** Decodes {@code data} as a long; see {@link #asInt(TextCodec, CharSequence, TypeDescriptor, CodecContext)}. */
  public static long asLong(TextCodec codec, CharSequence data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (codec instanceof PrimitiveTextDecoder) {
      return ((PrimitiveTextDecoder) codec).decodeAsLong(data, type, ctx);
    }
    return boxToLong(codec.decodeText(data, type, ctx));
  }

  /** Decodes {@code data} as a float; see {@link #asInt(TextCodec, CharSequence, TypeDescriptor, CodecContext)}. */
  public static float asFloat(TextCodec codec, CharSequence data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (codec instanceof PrimitiveTextDecoder) {
      return ((PrimitiveTextDecoder) codec).decodeAsFloat(data, type, ctx);
    }
    return boxToFloat(codec.decodeText(data, type, ctx));
  }

  /** Decodes {@code data} as a double; see {@link #asInt(TextCodec, CharSequence, TypeDescriptor, CodecContext)}. */
  public static double asDouble(TextCodec codec, CharSequence data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (codec instanceof PrimitiveTextDecoder) {
      return ((PrimitiveTextDecoder) codec).decodeAsDouble(data, type, ctx);
    }
    return boxToDouble(codec.decodeText(data, type, ctx));
  }

  /** Decodes {@code data} as a boolean; see {@link #asInt(TextCodec, CharSequence, TypeDescriptor, CodecContext)}. */
  public static boolean asBoolean(TextCodec codec, CharSequence data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (codec instanceof PrimitiveTextDecoder) {
      return ((PrimitiveTextDecoder) codec).decodeAsBoolean(data, type, ctx);
    }
    return BooleanCoercion.castAndCheck(
        codec.decodeText(data, type, ctx), () -> codec.decodeAsString(data, type, ctx));
  }

  /**
   * Decodes {@code data} as a {@link BigDecimal} through {@code codec}'s native path when it
   * implements {@link PrimitiveTextDecoder}, otherwise by boxing through {@link TextCodec#decodeText}.
   */
  public static @Nullable BigDecimal asBigDecimal(TextCodec codec, CharSequence data,
      TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (codec instanceof PrimitiveTextDecoder) {
      return ((PrimitiveTextDecoder) codec).decodeAsBigDecimal(data, type, ctx);
    }
    return boxToBigDecimal(codec.decodeText(data, type, ctx));
  }

  /**
   * Decodes ASCII text bytes as an int through {@code codec}'s native fast path when it implements
   * {@link PrimitiveTextDecoder}, otherwise by decoding the bytes to a {@code String} and boxing.
   */
  public static int asIntFromTextBytes(TextCodec codec, byte[] data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (codec instanceof PrimitiveTextDecoder) {
      return ((PrimitiveTextDecoder) codec).decodeTextBytesAsInt(data, type, ctx);
    }
    return asInt(codec, new String(data, ctx.getCharset()), type, ctx);
  }

  /** Decodes ASCII text bytes as a long; see {@link #asIntFromTextBytes}. */
  public static long asLongFromTextBytes(TextCodec codec, byte[] data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (codec instanceof PrimitiveTextDecoder) {
      return ((PrimitiveTextDecoder) codec).decodeTextBytesAsLong(data, type, ctx);
    }
    return asLong(codec, new String(data, ctx.getCharset()), type, ctx);
  }

  // ===========================================================================
  // Boxing fallbacks, shared with the interface defaults
  // ===========================================================================

  private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
  private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
  // (double) Long.MIN_VALUE is exactly -2^63; 2^63 is the first double at or above Long.MAX_VALUE + 1.
  private static final double LONG_MIN_AS_DOUBLE = -0x1p63;
  private static final double TWO_POW_63 = 0x1p63;

  static int boxToInt(@Nullable Object value) throws SQLException {
    if (value instanceof Number) {
      long asLong = numberToLong((Number) value, "int");
      if (asLong < Integer.MIN_VALUE || asLong > Integer.MAX_VALUE) {
        throw Exceptions.valueOutOfRange(value, "int");
      }
      return (int) asLong;
    }
    throw Codecs.cannotDecode(value, "int");
  }

  static long boxToLong(@Nullable Object value) throws SQLException {
    if (value instanceof Number) {
      return numberToLong((Number) value, "long");
    }
    throw Codecs.cannotDecode(value, "long");
  }

  /**
   * Narrows a {@link Number} to a {@code long}, truncating any fractional part toward zero. Unlike a
   * bare {@link Number#longValue()} -- which wraps a too-large integer and turns {@code NaN}/{@code
   * Infinity} into 0 or a saturated bound -- this refuses a value outside {@code long} range or a
   * non-finite float/double with {@link Exceptions#valueOutOfRange}. {@link #boxToInt} then range-checks
   * the result to {@code int}, so the report names the caller's target type.
   */
  private static long numberToLong(Number value, String targetType) throws SQLException {
    if (value instanceof Long || value instanceof Integer || value instanceof Short
        || value instanceof Byte) {
      return value.longValue();
    }
    if (value instanceof BigInteger) {
      return bigIntegerToLong((BigInteger) value, value, targetType);
    }
    if (value instanceof BigDecimal) {
      return bigIntegerToLong(((BigDecimal) value).toBigInteger(), value, targetType);
    }
    double d = value.doubleValue();
    if (Double.isNaN(d) || d < LONG_MIN_AS_DOUBLE || d >= TWO_POW_63) {
      throw Exceptions.valueOutOfRange(value, targetType);
    }
    return (long) d;
  }

  private static long bigIntegerToLong(BigInteger whole, Object original, String targetType)
      throws SQLException {
    if (whole.compareTo(LONG_MIN) < 0 || whole.compareTo(LONG_MAX) > 0) {
      throw Exceptions.valueOutOfRange(original, targetType);
    }
    return whole.longValue();
  }

  static float boxToFloat(@Nullable Object value) throws SQLException {
    if (value instanceof Number) {
      float result = ((Number) value).floatValue();
      // A finite value that overflows to +/-Infinity, or a nonzero value that underflows to zero,
      // does not fit float -- refuse rather than silently saturate, matching PostgreSQL's
      // float8->float4 cast and {@link #boxToInt}'s range check. A genuine NaN/Infinity source (only a
      // Double/Float can be one) passes through, since float represents it.
      boolean overflow = Float.isInfinite(result) && isFiniteSource(value);
      boolean underflow = result == 0.0f && isNonZero((Number) value);
      if (overflow || underflow) {
        throw Exceptions.valueOutOfRange(value, "float");
      }
      return result;
    }
    throw Codecs.cannotDecode(value, "float");
  }

  /** Whether {@code value} is finite; only a {@link Double}/{@link Float} can be non-finite. */
  private static boolean isFiniteSource(Object value) {
    if (value instanceof Double) {
      return Double.isFinite((Double) value);
    }
    if (value instanceof Float) {
      return Float.isFinite((Float) value);
    }
    return true;
  }

  /** Whether {@code value} is a nonzero number, tested exactly for {@code BigDecimal}/{@code BigInteger}. */
  private static boolean isNonZero(Number value) {
    if (value instanceof BigDecimal) {
      return ((BigDecimal) value).signum() != 0;
    }
    if (value instanceof BigInteger) {
      return ((BigInteger) value).signum() != 0;
    }
    return value.doubleValue() != 0.0;
  }

  static double boxToDouble(@Nullable Object value) throws SQLException {
    if (value instanceof Number) {
      double result = ((Number) value).doubleValue();
      // A finite value (only a BigDecimal/BigInteger can be this large) that overflows to +/-Infinity
      // does not fit double -- refuse rather than silently saturate, mirroring boxToFloat and the
      // server's numeric->float8 cast. A genuine NaN/Infinity source passes through. Unlike float,
      // underflow to zero is not checked: it needs a value below the smallest subnormal double
      // (~5e-324), far outside anything a numeric round-trip produces.
      if (Double.isInfinite(result) && isFiniteSource(value)) {
        throw Exceptions.valueOutOfRange(value, "double");
      }
      return result;
    }
    throw Codecs.cannotDecode(value, "double");
  }

  static @Nullable BigDecimal boxToBigDecimal(@Nullable Object value) throws SQLException {
    if (value == null) {
      return null;
    }
    if (value instanceof BigDecimal) {
      return (BigDecimal) value;
    }
    if (value instanceof Number) {
      if (value instanceof Long || value instanceof Integer || value instanceof Short
          || value instanceof Byte) {
        return BigDecimal.valueOf(((Number) value).longValue());
      }
      double doubleValue = ((Number) value).doubleValue();
      // BigDecimal has no non-finite form, so a NaN or infinite float refuses rather than letting
      // BigDecimal.valueOf throw an unchecked NumberFormatException.
      if (Double.isNaN(doubleValue) || Double.isInfinite(doubleValue)) {
        throw Exceptions.valueOutOfRange(value, "BigDecimal");
      }
      return BigDecimal.valueOf(doubleValue);
    }
    throw Codecs.cannotDecode(value, "BigDecimal");
  }
}
