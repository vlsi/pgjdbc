/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingByteArrayOutputStream;
import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.CodecFormatSupport;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.StreamingTextCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.util.PGBinaryObject;
import org.postgresql.util.PGobject;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Adapter codec for a {@link PGobject} subclass registered through
 * {@link org.postgresql.PGConnection#addDataType(String, Class)}.
 *
 * <p>This decorates the codec that would otherwise handle the type (the
 * <em>delegate</em>) and overrides the paths where the registered class speaks
 * for itself. On decode a value is materialized as that subclass, populated
 * through {@link PGBinaryObject#setByteValue(byte[], int)} when the class
 * supports binary and the data is binary, otherwise through
 * {@link PGobject#setValue(String)} with the value's text literal. On encode an
 * instance of that class writes back the representation it carries — its own
 * bytes through {@link PGBinaryObject#toBytes(byte[], int)}, or its own text
 * literal — so a value read from a column can be written back to it. Everything
 * else — a value of another class, {@code getString}/{@code getInt}-style
 * coercions, {@code SQLData} targets — is forwarded to the delegate
 * unchanged.</p>
 *
 * <p>Registering the adapter by OID makes it apply wherever the codec layer
 * resolves the type: top-level columns, array elements, and composite fields.
 * It is keyed by OID, so the registered identifier form (bare, schema-qualified,
 * or quoted) no longer matters for resolution.</p>
 */
public final class PGobjectCodec implements StreamingBinaryCodec, StreamingTextCodec {

  private final Class<? extends PGobject> pgObjectClass;
  private final Codec delegate;
  private final boolean binaryObject;

  /**
   * Creates an adapter for {@code pgObjectClass} backed by {@code delegate}.
   *
   * @param pgObjectClass the registered PGobject subclass
   * @param delegate the codec that would otherwise handle the type
   */
  public PGobjectCodec(Class<? extends PGobject> pgObjectClass, Codec delegate) {
    this.pgObjectClass = pgObjectClass;
    this.delegate = delegate;
    this.binaryObject = PGBinaryObject.class.isAssignableFrom(pgObjectClass);
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return pgObjectClass;
  }

  // ---- Decode: produce the registered PGobject subclass --------------------

  @Override
  public @Nullable Object decodeText(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    // The PGobject keeps the text as its value, so it must own a String rather than the caller's
    // borrowed view.
    return fromText(data.toString(), type);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (binaryObject) {
      return fromBinary(data, offset, type);
    }
    // A non-binary PGobject subclass is populated from text, so render the
    // binary wire through the delegate first.
    String text = delegate instanceof BinaryCodec
        ? ((BinaryCodec) delegate).decodeAsString(data, offset, length, type, ctx)
        : null;
    return text == null ? null : fromText(text, type);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(CharSequence data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass.isAssignableFrom(pgObjectClass)) {
      return (T) fromText(data.toString(), type);
    }
    if (delegate instanceof TextCodec) {
      return ((TextCodec) delegate).decodeTextAs(data, type, targetClass, ctx);
    }
    throw Exceptions.cannotDecode(type.getFormattedName(), targetClass.getName());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    if (targetClass.isAssignableFrom(pgObjectClass)) {
      return (T) decodeBinary(data, offset, length, type, ctx);
    }
    if (delegate instanceof BinaryCodec) {
      return ((BinaryCodec) delegate).decodeBinaryAs(data, offset, length, type, targetClass, ctx);
    }
    throw Exceptions.cannotDecode(type.getFormattedName(), targetClass.getName());
  }

  // ---- Encode: the registered class encodes itself, everything else delegates ----

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String own = ownText(value, type, ctx);
    if (own != null) {
      return own;
    }
    if (delegate instanceof TextCodec) {
      return ((TextCodec) delegate).encodeText(value, type, ctx);
    }
    throw Exceptions.cannotEncode(value, type.getFormattedName());
  }

  @Override
  public void encodeText(Object value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    String own = ownText(value, type, ctx);
    if (own != null) {
      out.append(own);
    } else if (delegate instanceof StreamingTextCodec) {
      ((StreamingTextCodec) delegate).encodeText(value, type, ctx, out);
    } else if (delegate instanceof TextCodec) {
      out.append(((TextCodec) delegate).encodeText(value, type, ctx));
    } else {
      throw Exceptions.cannotEncode(value, type.getFormattedName());
    }
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    byte @Nullable [] own = ownBinary(value);
    if (own != null) {
      return own;
    }
    if (delegate instanceof BinaryCodec) {
      return ((BinaryCodec) delegate).encodeBinary(value, type, ctx);
    }
    throw Exceptions.cannotEncode(value, type.getFormattedName());
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingByteArrayOutputStream out) throws SQLException, IOException {
    byte @Nullable [] own = ownBinary(value);
    if (own != null) {
      out.write(own);
    } else if (delegate instanceof StreamingBinaryCodec) {
      ((StreamingBinaryCodec) delegate).encodeBinary(value, type, ctx, out);
    } else if (delegate instanceof BinaryCodec) {
      out.write(((BinaryCodec) delegate).encodeBinary(value, type, ctx));
    } else {
      throw Exceptions.cannotEncode(value, type.getFormattedName());
    }
  }

  @Override
  public boolean encodesBinary() {
    // A PGBinaryObject subclass writes the binary wire itself (ownBinary), the encode-side mirror
    // of decodesBinary(); any other subclass emits binary only through the delegate.
    return binaryObject || (delegate instanceof BinaryCodec && ((BinaryCodec) delegate).encodesBinary());
  }

  @Override
  public boolean canEncodeBinaryType(TypeDescriptor type, CodecContext ctx) throws SQLException {
    // toBytes serializes the whole value in one piece, so a PGBinaryObject subclass binds binary
    // even for a type whose delegate embeds a text-only child.
    return binaryObject
        || (delegate instanceof BinaryCodec && ((BinaryCodec) delegate).canEncodeBinaryType(type, ctx));
  }

  @Override
  public boolean canEncodeBinaryValue(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (pgObjectClass.isInstance(value)) {
      // The delegate cannot encode the registered class at all, so its answer would be a false yes:
      // an instance binds binary exactly when it carries a binary form of its own, and otherwise
      // negotiates text, where its own literal answers.
      return hasOwnBinary(value);
    }
    return delegate instanceof BinaryCodec
        && ((BinaryCodec) delegate).canEncodeBinaryValue(value, type, ctx);
  }

  @Override
  public boolean canEncodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (pgObjectClass.isInstance(value)) {
      return hasOwnBinary(value);
    }
    return delegate instanceof BinaryCodec
        && ((BinaryCodec) delegate).canEncodeBinary(value, type, ctx);
  }

  @Override
  public boolean decodesBinary() {
    // A PGBinaryObject subclass reads the binary wire itself (fromBinary); any other subclass renders
    // binary only by delegating to the underlying codec, so it reads binary exactly when the delegate
    // does. Without this the adapter would inherit the default true and claim a binary receive its
    // text-only delegate cannot honour, decoding a non-null value as null.
    return binaryObject || CodecFormatSupport.canReadBinary(delegate);
  }

  @Override
  public boolean mayRequireQuoting(TypeDescriptor type, CodecContext ctx) throws SQLException {
    return !(delegate instanceof TextCodec) || ((TextCodec) delegate).mayRequireQuoting(type, ctx);
  }

  @Override
  public @Nullable String decodeAsString(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (delegate instanceof TextCodec) {
      return ((TextCodec) delegate).decodeAsString(data, type, ctx);
    }
    return data.toString();
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (delegate instanceof BinaryCodec) {
      return ((BinaryCodec) delegate).decodeAsString(data, offset, length, type, ctx);
    }
    throw Exceptions.cannotDecode(type.getFormattedName(), "String");
  }

  // ---- The registered class's own representations ---------------------------

  /**
   * Whether {@code value} is an instance of the registered class carrying a binary representation of
   * its own. A zero-length one means the object holds no binary value -- an unpopulated instance, or
   * one that stands for SQL NULL -- so its text form answers for it instead.
   */
  private boolean hasOwnBinary(Object value) {
    return binaryObject && pgObjectClass.isInstance(value)
        && ((PGBinaryObject) value).lengthInBytes() > 0;
  }

  /**
   * The binary representation {@code value} carries itself, or {@code null} when it has none and the
   * delegate must encode it. This is the encode-side mirror of {@link #fromBinary}: the class that
   * reads the binary wire through {@link PGBinaryObject#setByteValue(byte[], int)} writes it back
   * through {@link PGBinaryObject#toBytes(byte[], int)}, so a value materialized from a binary column
   * round-trips instead of being refused by a delegate that wants its own Java class.
   */
  private byte @Nullable [] ownBinary(Object value) {
    if (!hasOwnBinary(value)) {
      return null;
    }
    PGBinaryObject binaryValue = (PGBinaryObject) value;
    byte[] data = new byte[binaryValue.lengthInBytes()];
    binaryValue.toBytes(data, 0);
    return data;
  }

  /**
   * The text representation {@code value} carries itself, or {@code null} when it has none and the
   * delegate must encode it. An instance populated from the binary wire has no text of its own, so
   * the binary form it does carry is read back through the delegate -- the same rendering
   * {@code getString} gives for that column.
   */
  private @Nullable String ownText(Object value, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (!pgObjectClass.isInstance(value)) {
      return null;
    }
    String text = ((PGobject) value).getValue();
    if (text != null) {
      return text;
    }
    byte @Nullable [] data = ownBinary(value);
    if (data == null || !(delegate instanceof BinaryCodec)) {
      return null;
    }
    return ((BinaryCodec) delegate).decodeAsString(data, 0, data.length, type, ctx);
  }

  // ---- Materialization -----------------------------------------------------

  private PGobject newInstance(TypeDescriptor type) throws SQLException {
    PGobject obj;
    try {
      obj = pgObjectClass.getConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw Exceptions.cannotInstantiate(pgObjectClass.getName(), e);
    }
    obj.setType(type.getFormattedName());
    return obj;
  }

  private PGobject fromText(String text, TypeDescriptor type) throws SQLException {
    PGobject obj = newInstance(type);
    obj.setValue(text);
    return obj;
  }

  private PGobject fromBinary(byte[] data, int offset, TypeDescriptor type) throws SQLException {
    PGobject obj = newInstance(type);
    ((PGBinaryObject) obj).setByteValue(data, offset);
    return obj;
  }
}
