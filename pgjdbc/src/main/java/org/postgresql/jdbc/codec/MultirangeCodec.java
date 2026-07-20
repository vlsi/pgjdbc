/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingByteArrayOutputStream;
import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.CodecFormatSupport;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.CodecDepth;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PGRange;
import org.postgresql.util.PGmultirange;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Codec for PostgreSQL multirange types (PostgreSQL 14+).
 *
 * <p>A multirange is an ordered list of non-overlapping {@link PGRange} values of one subtype, such
 * as {@code int4multirange}, {@code nummultirange}, or {@code tstzmultirange}. This codec composes
 * on top of {@link RangeCodec}: it owns the multirange framing (the {@code {...}} braces in text,
 * the count-and-length headers in binary) and delegates each range to the range codec, which
 * resolves the bound subtype in turn. The range type itself is taken from the multirange metadata
 * ({@code pg_range.rngtypid}, joined on {@code rngmultitypid}; see
 * {@link TypeDescriptor#getMultirangeRange()}).</p>
 *
 * <p>Text format: {@code {}} for an empty multirange, {@code {[1,5)}} for one range,
 * {@code {[1,5),[10,20)}} for several. The ranges are joined by commas; each is a plain
 * {@code range_out} literal, so a comma-containing bound is quoted at the range level and the
 * multirange parser hands the whole range literal to the range codec untouched.</p>
 *
 * <p>Binary format: an {@code int32} range count, then for each range an {@code int32} byte length
 * followed by that range's {@code range_send} payload — the same payload {@link RangeCodec}
 * produces and consumes.</p>
 */
public final class MultirangeCodec implements StreamingBinaryCodec, TextCodec {

  public static final MultirangeCodec INSTANCE = new MultirangeCodec();

  private MultirangeCodec() {
    // Singleton
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return PGmultirange.class;
  }

  // ==================== Binary Codec Methods ====================

  @Override
  public @Nullable Object decodeBinary(byte[] src, int srcOffset, int srcLength, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (srcLength == 0) {
      return null;
    }

    CodecDepth.enter();
    try {
      int rangeOid = type.getMultirangeRange();
      if (rangeOid == 0) {
        throw Exceptions.multirangeRangeTypeUnresolvedForDecode(type.getFormattedName());
      }
      TypeDescriptor rangeType = ctx.resolveType(rangeOid);
      BinaryCodec rangeCodec = ctx.resolveBinaryCodec(rangeOid);
      if (rangeCodec == null) {
        throw Exceptions.multirangeRangeCodecMissingForDecode(type.getFormattedName(), rangeOid);
      }

      // Bounds are tracked against the caller's slice (srcOffset..srcOffset + srcLength); every
      // read stays inside src without ever copying it into a fresh array.
      int end = srcOffset + srcLength;
      if (srcLength < 4) {
        throw Exceptions.invalidMultirangeMissingRangeCount();
      }
      int count = ByteConverter.int4(src, srcOffset);
      if (count < 0) {
        throw Exceptions.invalidMultirangeNegativeRangeCount(count);
      }

      int offset = srcOffset + 4;
      List<PGRange<Object>> ranges = new ArrayList<>(Math.min(count, 1024));
      for (int i = 0; i < count; i++) {
        if (offset + 4 > end) {
          throw Exceptions.invalidMultirangeMissingRangeLength();
        }
        int len = ByteConverter.int4(src, offset);
        offset += 4;
        if (len < 0 || offset + len > end) {
          throw Exceptions.invalidMultirangeRangeTruncated();
        }
        // Slice-decode each range in place: range_send is exactly what RangeCodec reads.
        PGRange<Object> range = asRange(rangeCodec.decodeBinary(src, offset, len, rangeType, ctx));
        offset += len;
        // The server drops empty ranges from a multirange; mirror the text path for symmetry.
        if (!range.isEmpty()) {
          ranges.add(range);
        }
      }
      return newMultirange(ranges, type);
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
      throw new AssertionError(e); // BackpatchingByteArrayOutputStream never throws
    }
    return out.toByteArray();
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingByteArrayOutputStream out) throws SQLException, IOException {
    if (!(value instanceof PGmultirange)) {
      throw Exceptions.cannotEncodeMultirange(value);
    }

    CodecDepth.enter();
    try {
      PGmultirange<?> multirange = (PGmultirange<?>) value;

      int rangeOid = type.getMultirangeRange();
      if (rangeOid == 0) {
        throw Exceptions.multirangeRangeTypeUnresolvedForEncode(type.getFormattedName());
      }
      TypeDescriptor rangeType = ctx.resolveType(rangeOid);
      BinaryCodec rangeCodec = ctx.resolveBinaryCodec(rangeOid);
      if (rangeCodec == null) {
        throw Exceptions.multirangeRangeCodecMissingForEncode(type.getFormattedName(), rangeOid);
      }

      List<? extends PGRange<?>> ranges = multirange.getRanges();
      out.writeInt32(ranges.size());
      for (PGRange<?> range : ranges) {
        CodecFormatSupport.writeBinaryElement(out, range, rangeCodec, rangeType, ctx);
      }
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    if (targetClass == PGmultirange.class || targetClass == Object.class) {
      return (T) decodeBinary(data, offset, length, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) decodeAsString(data, offset, length, type, ctx);
    }
    throw Exceptions.cannotDecodeMultirangeTo(targetClass.getName());
  }

  // ==================== Text Codec Methods ====================

  @Override
  public @Nullable Object decodeText(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (data == null || data.length() == 0) {
      return null;
    }
    LiteralCursor cur = LiteralCursor.over(data);
    Object multirange = decodeMultirange(cur, type, ctx);
    cur.expectEnd();
    return multirange;
  }

  /**
   * Parses a multirange literal off {@code cur}: a brace-delimited, comma-separated list of range
   * literals. Each range is peeled off the same cursor by {@link RangeCodec#decodeRange}, so a
   * comma-containing quoted bound stays inside its range rather than splitting the list.
   */
  private static @Nullable Object decodeMultirange(LiteralCursor cur, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    int rangeOid = type.getMultirangeRange();
    if (rangeOid == 0) {
      throw Exceptions.multirangeRangeTypeUnresolvedForDecodeText(type.getFormattedName());
    }
    TypeDescriptor rangeType = ctx.resolveType(rangeOid);

    CodecDepth.enter();
    try {
      cur.expect('{');
      List<PGRange<Object>> ranges = new ArrayList<>();
      // tryConsume('}') handles the empty multirange {} and the closing brace after the last range.
      if (!cur.tryConsume('}')) {
        do {
          PGRange<Object> range = asRange(RangeCodec.decodeRange(cur, rangeType, ctx));
          // The server drops empty ranges from a multirange ({empty} -> {}, {[1,2),empty} ->
          // {[1,2)}); match that so a hand-built literal decodes the way the server would.
          if (!range.isEmpty()) {
            ranges.add(range);
          }
        } while (cur.tryConsume(','));
        cur.expect('}');
      }
      return newMultirange(ranges, type);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (value instanceof PGmultirange) {
      return value.toString();
    }
    throw Exceptions.cannotEncodeMultirange(value);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(CharSequence data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == PGmultirange.class || targetClass == Object.class) {
      return (T) decodeText(data, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) data.toString();
    }
    throw Exceptions.cannotDecodeMultirangeTo(targetClass.getName());
  }

  @Override
  public @Nullable String decodeAsString(CharSequence data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return data.toString();
  }

  // ==================== Helpers ====================

  /** Wraps the decoded ranges in a {@link PGmultirange} labelled with the multirange's type name. */
  private static PGmultirange<Object> newMultirange(List<PGRange<Object>> ranges, TypeDescriptor type) {
    PGmultirange<Object> multirange = new PGmultirange<>(ranges);
    multirange.setType(type.getFormattedName());
    return multirange;
  }

  /** Casts a decoded range, failing clearly if the range codec produced something else. */
  @SuppressWarnings("unchecked")
  private static PGRange<Object> asRange(@Nullable Object decoded) throws SQLException {
    if (decoded instanceof PGRange) {
      return (PGRange<Object>) decoded;
    }
    throw Exceptions.multirangeElementNotARange(decoded == null ? "null" : decoded.getClass().getName());
  }
}
