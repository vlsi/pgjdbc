/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingByteArrayOutputStream;
import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.CodecFormatSupport;
import org.postgresql.api.codec.PrimitiveBinaryDecoder;
import org.postgresql.api.codec.PrimitiveDecoders;
import org.postgresql.api.codec.PrimitiveTextDecoder;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.StreamingTextCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.CodecDepth;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL domain types.
 *
 * <p>A domain is a base type carrying optional constraints, for example
 * {@code CREATE DOMAIN positive_int AS integer CHECK (value > 0)}; {@code positive_int} is encoded
 * and decoded by {@link Int4Codec}.</p>
 *
 * <h2>Contract: a domain is unwrapped transparently to its base type</h2>
 *
 * <p>This codec resolves the domain to its base type ({@code pg_type.typbasetype}) and forwards
 * the wire bytes to the base type's codec, passing the <em>base</em> {@link TypeDescriptor}. Two
 * consequences follow, and both are intentional:</p>
 *
 * <ul>
 *   <li><strong>The base codec sees the base type, not the domain.</strong> A caller that decodes
 *   a domain value receives the Java type of the base type — a {@code positive_int} value comes
 *   back as an {@link Integer}, exactly as a plain {@code integer} would. The DISTINCT identity of
 *   the domain is not reflected in the decoded Java object; it is visible only through metadata
 *   ({@code ResultSetMetaData.getColumnTypeName}, {@code DatabaseMetaData}), which report the
 *   domain rather than its base type.</li>
 *
 *   <li><strong>The domain's type modifier is propagated on decode.</strong> A domain may pin a
 *   typmod on its base type (for example {@code CREATE DOMAIN price AS numeric(10,2)}), stored in
 *   {@code pg_type.typtypmod}. Through {@link TypeDescriptor#withTypmod(int)} this codec forwards the
 *   modifier the domain column applies, falling back to that pinned one when the column applies none,
 *   so a modifier-sensitive base codec — numeric rescaling to the declared scale, for instance —
 *   observes it through {@link TypeDescriptor#getAppliedTypmod()}.
 *   Encode is unaffected: the numeric codecs encode from the value's own scale and the server enforces the
 *   domain constraint on input regardless. The domain's own {@link TypeDescriptor#getCatalogTypmod()} is
 *   left unchanged for metadata such as column-size reporting.</li>
 * </ul>
 */
public final class DomainCodec implements StreamingBinaryCodec, StreamingTextCodec,
    PrimitiveBinaryDecoder, PrimitiveTextDecoder {

  public static final DomainCodec INSTANCE = new DomainCodec();

  private DomainCodec() {
    // Singleton
  }

  /**
   * Gets the base type codec for the given domain type.
   */
  private static Codec getBaseCodec(TypeDescriptor domainType, CodecContext ctx) throws SQLException {
    int baseTypeOid = domainType.getTypbasetype();
    if (baseTypeOid == 0) {
      // Not a domain, fall back to default behavior
      return FallbackCodec.INSTANCE;
    }
    return ctx.resolveCodec(baseTypeOid);
  }

  /**
   * Gets the base type for the given domain type.
   */
  private static TypeDescriptor getBaseType(TypeDescriptor domainType, CodecContext ctx) throws SQLException {
    int baseTypeOid = domainType.getTypbasetype();
    if (baseTypeOid == 0) {
      return domainType;
    }
    // -1 means the column applies no modifier; the class contract covers the fallback to the pinned one.
    int typmod = domainType.getAppliedTypmod() != -1 ? domainType.getAppliedTypmod() : domainType.getCatalogTypmod();
    return ctx.resolveType(baseTypeOid, typmod);
  }

  @Override
  public Class<?> getDefaultJavaType() {
    // Domain's default Java type depends on the base type
    return Object.class;
  }

  @Override
  public boolean canEncodeBinaryType(TypeDescriptor type, CodecContext ctx) throws SQLException {
    // A domain is transparent: it binds binary exactly when its base type does, so recurse into the
    // base (which itself recurses if it is a container).
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      return baseCodec instanceof BinaryCodec
          && ((BinaryCodec) baseCodec).canEncodeBinaryType(getBaseType(type, ctx), ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public boolean canEncodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    // Local (enforcement): a domain is transparent, so forward the value check to the base.
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      return baseCodec instanceof BinaryCodec
          && ((BinaryCodec) baseCodec).canEncodeBinary(value, getBaseType(type, ctx), ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public boolean canEncodeBinaryValue(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    // Negotiation: forward the recursive value walk to the base, so a base container recurses.
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      return baseCodec instanceof BinaryCodec
          && ((BinaryCodec) baseCodec).canEncodeBinaryValue(value, getBaseType(type, ctx), ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return ((BinaryCodec) baseCodec).decodeBinary(data, offset, length, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeBinary(data, offset, length, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      // A domain is transparent: it binds binary only when its base type genuinely encodes binary.
      // A base with no real binary codec must fall back to text at the format-choice site, so encode
      // refuses here rather than emitting text-shaped bytes (FallbackCodec's) into the binary wire.
      BinaryCodec binary = CodecFormatSupport.requireBinaryEncoder(baseCodec, value, baseType, ctx);
      return binary.encodeBinary(value, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingByteArrayOutputStream out) throws SQLException, IOException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      BinaryCodec binary = CodecFormatSupport.requireBinaryEncoder(baseCodec, value, baseType, ctx);
      CodecFormatSupport.writeBinary(out, value, binary, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public boolean mayRequireQuoting(TypeDescriptor type, CodecContext ctx) throws SQLException {
    // A domain is transparent: its rendered text is its base type's, so its composite/array quoting
    // need is the base's rather than the pessimistic default. Without this a domain over a quote-safe
    // base (int4, numeric) is quoted inside a record -- ("5",...) where the server writes (5,...).
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      return !(baseCodec instanceof TextCodec)
          || ((TextCodec) baseCodec).mayRequireQuoting(getBaseType(type, ctx), ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public @Nullable Object decodeText(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return ((TextCodec) baseCodec).decodeText(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeText(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return ((TextCodec) baseCodec).encodeText(value, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.encodeText(value, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public void encodeText(Object value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof StreamingTextCodec) {
        ((StreamingTextCodec) baseCodec).encodeText(value, baseType, ctx, out);
      } else if (baseCodec instanceof TextCodec) {
        out.append(((TextCodec) baseCodec).encodeText(value, baseType, ctx));
      } else {
        out.append(FallbackCodec.INSTANCE.encodeText(value, baseType, ctx));
      }
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return ((BinaryCodec) baseCodec).decodeBinaryAs(data, offset, length, baseType, targetClass, ctx);
      }
      return FallbackCodec.INSTANCE.decodeBinaryAs(data, offset, length, baseType, targetClass, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public <T> @Nullable T decodeTextAs(CharSequence data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return ((TextCodec) baseCodec).decodeTextAs(data, baseType, targetClass, ctx);
      }
      return FallbackCodec.INSTANCE.decodeTextAs(data, baseType, targetClass, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  // A domain shares its base type's wire form, so the primitive accessors forward the slice (binary)
  // or string (text) straight to the base codec's own no-box path via PrimitiveDecoders, which boxes
  // through the base's decodeBinary/decodeText only when the base is not itself a primitive decoder.

  @Override
  public int decodeAsInt(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return PrimitiveDecoders.asInt((BinaryCodec) baseCodec, data, offset, length, baseType, ctx);
      }
      return PrimitiveDecoders.asInt(FallbackCodec.INSTANCE, data, offset, length, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public long decodeAsLong(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return PrimitiveDecoders.asLong((BinaryCodec) baseCodec, data, offset, length, baseType, ctx);
      }
      return PrimitiveDecoders.asLong(FallbackCodec.INSTANCE, data, offset, length, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public float decodeAsFloat(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return PrimitiveDecoders.asFloat((BinaryCodec) baseCodec, data, offset, length, baseType, ctx);
      }
      return PrimitiveDecoders.asFloat(FallbackCodec.INSTANCE, data, offset, length, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public double decodeAsDouble(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return PrimitiveDecoders.asDouble((BinaryCodec) baseCodec, data, offset, length, baseType, ctx);
      }
      return PrimitiveDecoders.asDouble(FallbackCodec.INSTANCE, data, offset, length, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public boolean decodeAsBoolean(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return PrimitiveDecoders.asBoolean((BinaryCodec) baseCodec, data, offset, length, baseType, ctx);
      }
      return PrimitiveDecoders.asBoolean(FallbackCodec.INSTANCE, data, offset, length, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public int decodeAsInt(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return PrimitiveDecoders.asInt((TextCodec) baseCodec, data, baseType, ctx);
      }
      return PrimitiveDecoders.asInt(FallbackCodec.INSTANCE, data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public long decodeAsLong(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return PrimitiveDecoders.asLong((TextCodec) baseCodec, data, baseType, ctx);
      }
      return PrimitiveDecoders.asLong(FallbackCodec.INSTANCE, data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public float decodeAsFloat(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return PrimitiveDecoders.asFloat((TextCodec) baseCodec, data, baseType, ctx);
      }
      return PrimitiveDecoders.asFloat(FallbackCodec.INSTANCE, data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public double decodeAsDouble(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return PrimitiveDecoders.asDouble((TextCodec) baseCodec, data, baseType, ctx);
      }
      return PrimitiveDecoders.asDouble(FallbackCodec.INSTANCE, data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public boolean decodeAsBoolean(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return PrimitiveDecoders.asBoolean((TextCodec) baseCodec, data, baseType, ctx);
      }
      return PrimitiveDecoders.asBoolean(FallbackCodec.INSTANCE, data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return PrimitiveDecoders.asBigDecimal((BinaryCodec) baseCodec, data, offset, length, baseType, ctx);
      }
      return PrimitiveDecoders.asBigDecimal(FallbackCodec.INSTANCE, data, offset, length, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return PrimitiveDecoders.asBigDecimal((TextCodec) baseCodec, data, baseType, ctx);
      }
      return PrimitiveDecoders.asBigDecimal(FallbackCodec.INSTANCE, data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof BinaryCodec) {
        return ((BinaryCodec) baseCodec).decodeAsString(data, offset, length, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsString(data, offset, length, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public @Nullable String decodeAsString(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      Codec baseCodec = getBaseCodec(type, ctx);
      TypeDescriptor baseType = getBaseType(type, ctx);
      if (baseCodec instanceof TextCodec) {
        return ((TextCodec) baseCodec).decodeAsString(data, baseType, ctx);
      }
      return FallbackCodec.INSTANCE.decodeAsString(data, baseType, ctx);
    } finally {
      CodecDepth.exit();
    }
  }
}
