/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.api.codec.BackpatchingByteArrayOutputStream;
import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.CodecFormatSupport;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.CodecDepth;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PGRange;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * Codec for PostgreSQL range types.
 *
 * <p>This codec handles encoding and decoding of PostgreSQL range types such as
 * int4range, int8range, numrange, tsrange, tstzrange, and daterange.</p>
 *
 * <p>Range types have a text format like: [lower,upper), (lower,upper], [,upper), etc.
 * The first character is '[' (inclusive) or '(' (exclusive) for the lower bound,
 * followed by the lower value (or empty for unbounded), a comma, the upper value
 * (or empty for unbounded), and finally ']' (inclusive) or ')' (exclusive).</p>
 *
 * <p>Binary format consists of:</p>
 * <ul>
 *   <li>1 byte flags: empty (0x01), lower inclusive (0x02), upper inclusive (0x04),
 *       lower infinite (0x08), upper infinite (0x10)</li>
 *   <li>If lower bound exists: 4-byte length + bound data</li>
 *   <li>If upper bound exists: 4-byte length + bound data</li>
 * </ul>
 */
public final class RangeCodec implements StreamingBinaryCodec, TextCodec {

  public static final RangeCodec INSTANCE = new RangeCodec();

  // Binary format flags
  private static final byte FLAG_EMPTY = 0x01;
  private static final byte FLAG_LOWER_INCLUSIVE = 0x02;
  private static final byte FLAG_UPPER_INCLUSIVE = 0x04;
  private static final byte FLAG_LOWER_INFINITE = 0x08;
  private static final byte FLAG_UPPER_INFINITE = 0x10;

  private RangeCodec() {
    // Singleton
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return PGRange.class;
  }

  @Override
  public boolean canEncodeBinaryType(TypeDescriptor type, CodecContext ctx) throws SQLException {
    // range_send serializes each bound with the subtype's binary output, so a range binds binary
    // only when its subtype does. Recurse into the subtype (which recurses further if it is itself
    // a container).
    CodecDepth.enter();
    try {
      int subtypeOid = type.getRangeSubtype();
      if (subtypeOid == 0) {
        return false;
      }
      BinaryCodec subtypeCodec = ctx.resolveBinaryCodec(subtypeOid);
      return subtypeCodec != null
          && subtypeCodec.canEncodeBinaryType(ctx.resolveType(subtypeOid), ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public boolean canEncodeBinaryValue(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (!(value instanceof PGRange)) {
      return canEncodeBinary(value, type, ctx);
    }
    PGRange<?> range = (PGRange<?>) value;
    if (range.isEmpty()) {
      // An empty range is a single flags byte with no bounds to encode, so its subtype is irrelevant.
      return true;
    }
    // Negotiation: recurse into each present bound so a bound the subtype codec cannot binary-encode
    // (a plain PGobject over a composite subtype, say) makes the range negotiate text.
    CodecDepth.enter();
    try {
      int subtypeOid = type.getRangeSubtype();
      if (subtypeOid == 0) {
        return false;
      }
      BinaryCodec subtypeCodec = ctx.resolveBinaryCodec(subtypeOid);
      if (subtypeCodec == null) {
        return false;
      }
      TypeDescriptor subtypeType = ctx.resolveType(subtypeOid);
      if (range.hasLowerBound()
          && !subtypeCodec.canEncodeBinaryValue(castNonNull(range.getLower()), subtypeType, ctx)) {
        return false;
      }
      return !range.hasUpperBound()
          || subtypeCodec.canEncodeBinaryValue(castNonNull(range.getUpper()), subtypeType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  // ==================== Binary Codec Methods ====================

  @Override
  public @Nullable Object decodeBinary(byte[] buf, int start, int len, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (len == 0) {
      return null;
    }
    // Range binary parsing indexes from the value start; copy only for a genuine sub-slice.
    byte[] data = start == 0 && len == buf.length ? buf : Arrays.copyOfRange(buf, start, start + len);

    CodecDepth.enter();
    try {
      byte flags = data[0];

      // Check for empty range
      if ((flags & FLAG_EMPTY) != 0) {
        PGRange<Object> range = PGRange.empty();
        range.setType(type.getFormattedName());
        return range;
      }

      boolean lowerInclusive = (flags & FLAG_LOWER_INCLUSIVE) != 0;
      boolean upperInclusive = (flags & FLAG_UPPER_INCLUSIVE) != 0;
      boolean lowerInfinite = (flags & FLAG_LOWER_INFINITE) != 0;
      boolean upperInfinite = (flags & FLAG_UPPER_INFINITE) != 0;

      // Resolve the subtype codec. pg_type.typelem is 0 for ranges; the real subtype lives in
      // pg_range.rngsubtype, which a descriptor reaching a codec already carries.
      int subtypeOid = type.getRangeSubtype();
      if (subtypeOid == 0) {
        throw Exceptions.rangeSubtypeUnresolvedForDecode(type.getFormattedName());
      }
      TypeDescriptor subtypeType = ctx.resolveType(subtypeOid);
      BinaryCodec subtypeCodec = ctx.resolveBinaryCodec(subtypeOid);
      if (subtypeCodec == null) {
        throw Exceptions.rangeSubtypeCodecMissingForDecode(type.getFormattedName(), subtypeOid);
      }

      int offset = 1;
      Object lower = null;
      Object upper = null;

      // Read lower bound if not infinite
      if (!lowerInfinite) {
        if (offset + 4 > data.length) {
          throw Exceptions.invalidRangeMissingLowerBoundLength();
        }
        int lowerLen = ByteConverter.int4(data, offset);
        offset += 4;
        if (lowerLen >= 0) {
          if (offset + lowerLen > data.length) {
            throw Exceptions.invalidRangeLowerBoundTruncated();
          }
          lower = subtypeCodec.decodeBinary(data, offset, lowerLen, subtypeType, ctx);
          offset += lowerLen;
        }
      }

      // Read upper bound if not infinite
      if (!upperInfinite) {
        if (offset + 4 > data.length) {
          throw Exceptions.invalidRangeMissingUpperBoundLength();
        }
        int upperLen = ByteConverter.int4(data, offset);
        offset += 4;
        if (upperLen >= 0) {
          if (offset + upperLen > data.length) {
            throw Exceptions.invalidRangeUpperBoundTruncated();
          }
          upper = subtypeCodec.decodeBinary(data, offset, upperLen, subtypeType, ctx);
        }
      }

      PGRange<Object> range = new PGRange<>(lower, upper, lowerInclusive, upperInclusive);
      range.setType(type.getFormattedName());
      return range;
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    BackpatchingByteArrayOutputStream out = new BackpatchingByteArrayOutputStream();
    try {
      encodeBinary(value, type, ctx, out);
    } catch (IOException e) {
      // BackpatchingByteArrayOutputStream never throws; keep the historical error mapping regardless.
      throw Exceptions.errorEncodingRange(e);
    }
    return out.toByteArray();
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingByteArrayOutputStream out) throws SQLException, IOException {
    if (!(value instanceof PGRange)) {
      throw Exceptions.cannotEncodeRange(value);
    }

    CodecDepth.enter();
    try {
      @SuppressWarnings("unchecked")
      PGRange<Object> range = (PGRange<Object>) value;

      if (range.isEmpty()) {
        out.writeByte(FLAG_EMPTY);
        return;
      }

      // Resolve the subtype codec. pg_type.typelem is 0 for ranges; the real subtype lives in
      // pg_range.rngsubtype, which a descriptor reaching a codec already carries.
      int subtypeOid = type.getRangeSubtype();
      if (subtypeOid == 0) {
        throw Exceptions.rangeSubtypeUnresolvedForEncode(type.getFormattedName());
      }
      TypeDescriptor subtypeType = ctx.resolveType(subtypeOid);
      BinaryCodec subtypeCodec = ctx.resolveBinaryCodec(subtypeOid);
      if (subtypeCodec == null) {
        throw Exceptions.rangeSubtypeCodecMissingForEncode(type.getFormattedName(), subtypeOid);
      }

      // Calculate flags
      byte flags = 0;
      if (range.isLowerInclusive()) {
        flags |= FLAG_LOWER_INCLUSIVE;
      }
      if (range.isUpperInclusive()) {
        flags |= FLAG_UPPER_INCLUSIVE;
      }
      if (!range.hasLowerBound()) {
        flags |= FLAG_LOWER_INFINITE;
      }
      if (!range.hasUpperBound()) {
        flags |= FLAG_UPPER_INFINITE;
      }
      out.writeByte(flags);

      // Write lower bound if not infinite
      if (range.hasLowerBound()) {
        Object bound = castNonNull(range.getLower());
        CodecFormatSupport.writeBinaryElement(out, bound, subtypeCodec, subtypeType, ctx);
      }

      // Write upper bound if not infinite
      if (range.hasUpperBound()) {
        Object bound = castNonNull(range.getUpper());
        CodecFormatSupport.writeBinaryElement(out, bound, subtypeCodec, subtypeType, ctx);
      }
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    if (targetClass == PGRange.class || targetClass == Object.class) {
      return (T) decodeBinary(data, offset, length, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) decodeAsString(data, offset, length, type, ctx);
    }
    throw Exceptions.cannotDecodeRangeTo(targetClass.getName());
  }

  // ==================== Text Codec Methods ====================

  @Override
  public @Nullable Object decodeText(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (data == null || data.length() == 0) {
      return null;
    }
    LiteralCursor cur = LiteralCursor.over(data);
    Object range = decodeRange(cur, type, ctx);
    cur.expectEnd();
    return range;
  }

  /**
   * Parses one range literal off {@code cur}, driving the shared {@link LiteralCursor}
   * so the same code serves a {@code String} and a borrowed view alike. On return the cursor sits just
   * past the range's closing bracket, so {@link MultirangeCodec} can call this in a loop
   * to peel the ranges out of a {@code {...}} multirange literal off the same cursor.
   *
   * @param cur the cursor positioned at the start of a range literal
   * @param type the range type, used to resolve the bound subtype and label the result
   * @param ctx the codec context, or {@code null} to keep bounds as raw strings
   */
  static @Nullable Object decodeRange(LiteralCursor cur, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    cur.skipWhitespace();
    if (cur.consumeKeyword("empty")) {
      PGRange<Object> range = PGRange.empty();
      range.setType(type.getFormattedName());
      return range;
    }

    CodecDepth.enter();
    try {
      // pg_type.typelem is 0 for ranges; the real subtype lives in pg_range.rngsubtype, which a
      // descriptor reaching a codec already carries. With a connection-bound context the bounds
      // are decoded by the subtype's text codec into typed values; without one (the codec unit
      // tests, which pass a null context) the bounds are kept as their raw strings.
      int subtypeOid = type.getRangeSubtype();
      TypeDescriptor subtypeType = subtypeOid != 0 && ctx != null ? ctx.resolveType(subtypeOid) : null;
      Codec subtypeCodec = subtypeOid != 0 && ctx != null ? ctx.resolveCodec(subtypeOid) : null;
      TextCodec boundCodec =
          subtypeCodec instanceof TextCodec && subtypeType != null ? (TextCodec) subtypeCodec : null;

      char open = cur.peek();
      boolean lowerInclusive;
      if (open == '[') {
        lowerInclusive = true;
      } else if (open == '(') {
        lowerInclusive = false;
      } else {
        throw Exceptions.invalidRangeBoundaryFormat("'[' or '('", cur.literal());
      }
      cur.expect(open);

      // Lower bound, terminated by the ',' separator.
      cur.readVerbatim(',', ']', ')');
      Object lower = decodeBound(cur, boundCodec, subtypeType, ctx);
      cur.expect(',');

      // Upper bound, terminated by the ']' or ')' closing bracket.
      cur.readVerbatim(',', ']', ')');
      Object upper = decodeBound(cur, boundCodec, subtypeType, ctx);

      char close = cur.peek();
      boolean upperInclusive;
      if (close == ']') {
        upperInclusive = true;
      } else if (close == ')') {
        upperInclusive = false;
      } else {
        throw Exceptions.invalidRangeBoundaryFormat("']' or ')'", cur.literal());
      }
      cur.expect(close);

      PGRange<Object> range = new PGRange<>(lower, upper, lowerInclusive, upperInclusive);
      range.setType(type.getFormattedName());
      return range;
    } finally {
      CodecDepth.exit();
    }
  }

  /**
   * Decodes the cursor's current token as a range bound. An unquoted empty token
   * is an infinite/unbounded bound ({@code null}); otherwise the bound slice is
   * decoded by the subtype text codec when known, or kept as its raw string.
   * Whitespace is part of the bound, as it is for {@code range_parse_bound}, so
   * {@code [ ,b)} has a lower bound of one space rather than an infinite one.
   */
  private static @Nullable Object decodeBound(LiteralCursor cur, @Nullable TextCodec boundCodec,
      @Nullable TypeDescriptor subtypeType, CodecContext ctx) throws SQLException {
    if (!cur.tokenWasQuoted() && cur.tokenLength() == 0) {
      return null; // infinite / unbounded
    }
    if (boundCodec != null && subtypeType != null) {
      Object decoded = boundCodec.decodeText(cur.getToken(), subtypeType, ctx);
      if (decoded != null) {
        return decoded;
      }
    }
    return cur.getToken().toString();
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (value instanceof PGRange) {
      return value.toString();
    }
    throw Exceptions.cannotEncodeRange(value);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(CharSequence data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == PGRange.class || targetClass == Object.class) {
      return (T) decodeText(data, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) data.toString();
    }
    throw Exceptions.cannotDecodeRangeTo(targetClass.getName());
  }

  @Override
  public @Nullable String decodeAsString(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return data.toString();
  }
}
