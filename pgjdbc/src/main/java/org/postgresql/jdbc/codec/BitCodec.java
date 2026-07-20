/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveBinaryDecoder;
import org.postgresql.api.codec.PrimitiveTextDecoder;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.BooleanTypeUtil;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PGobject;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;

/**
 * Codec for PostgreSQL {@code bit} and {@code bit varying} (varbit) types.
 *
 * <p>Both share the wire format: text is a string of {@code '0'}/{@code '1'} (for example
 * {@code "0101"}); binary is an int4 bit count followed by the bits packed MSB-first into
 * {@code ceil(n/8)} bytes.</p>
 *
 * <p>{@code getObject()} on a scalar {@code bit} column is special-cased in {@code PgResultSet}
 * ({@code bit(1)} → {@link Boolean}, wider → {@link PGobject}), so this codec's default
 * representation is the {@link PGobject} bit string, matching the legacy fallback. On encode it also
 * accepts a {@link Boolean} ({@code "1"}/{@code "0"}), so a {@code boolean[]} binds correctly to
 * {@code bit[]}.</p>
 *
 * <p>Binary is fully supported in both directions: {@link #decodeBinary} parses the packed wire form
 * and {@link #encodeBinary} produces it. The packed binary is ~8× smaller than the text form, so
 * {@code bit}/{@code varbit} (scalars and arrays) are registered for binary transfer like the other
 * built-in types. The scalar {@code bit(1) → Boolean} contract is preserved by reading the bit count
 * from the int4 prefix when the value arrives in binary (see {@code PgResultSet}); array elements are
 * always {@link PGobject}, so {@code bit[]}/{@code varbit[]} decode to {@code PGobject[]} via the
 * array codec walker.</p>
 */
public final class BitCodec implements PrimitiveBinaryDecoder, PrimitiveTextDecoder {

  public static final BitCodec INSTANCE = new BitCodec();

  private BitCodec() {
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return PGobject.class;
  }

  // ----------------------------- decode -----------------------------

  /**
   * Refuses a bit string carrying anything but {@code '0'} and {@code '1'}, the only characters
   * {@code bit_in} accepts.
   *
   * <p>The length is left to the server: it checks the literal against the column's typmod on both
   * text and binary receive, and a decoded value came from a column that already satisfied it. The
   * characters cannot be left to the server the same way, because the packed binary form the driver
   * writes has no way to express a bad one &mdash; see {@link Exceptions#invalidBinaryDigit}.</p>
   *
   * @param bits the bit string to check
   * @param typeName the bit type it is being read as or written to
   * @throws SQLException if a character is not a binary digit
   */
  private static void requireBitString(CharSequence bits, String typeName) throws SQLException {
    for (int i = 0, len = bits.length(); i < len; i++) {
      char c = bits.charAt(i);
      if (c != '0' && c != '1') {
        throw Exceptions.invalidBinaryDigit(typeName, c);
      }
    }
  }

  @Override
  public @Nullable Object decodeText(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String text = data.toString();
    requireBitString(text, type.getName().getLocalName());
    return toPGobject(type, text);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return toPGobject(type, binaryToBitString(data, offset, length));
  }

  private static PGobject toPGobject(TypeDescriptor type, String bits) throws SQLException {
    PGobject obj = new PGobject();
    obj.setType(type.getName().getLocalName());
    obj.setValue(bits);
    return obj;
  }

  @Override
  public String decodeAsString(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String text = data.toString();
    requireBitString(text, type.getName().getLocalName());
    return text;
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return binaryToBitString(data, offset, length);
  }

  @Override
  public boolean decodeAsBoolean(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    requireBitString(data, type.getName().getLocalName());
    return BooleanTypeUtil.fromString(data);
  }

  @Override
  public boolean decodeAsBoolean(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return BooleanTypeUtil.fromString(binaryToBitString(data, offset, length));
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(CharSequence data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    String text = data.toString();
    requireBitString(text, type.getName().getLocalName());
    if (targetClass == String.class) {
      return (T) text;
    }
    if (targetClass == Boolean.class) {
      return (T) Boolean.valueOf(BooleanTypeUtil.fromString(text));
    }
    if (targetClass == PGobject.class || targetClass == Object.class) {
      return (T) toPGobject(type, text);
    }
    throw Exceptions.cannotDecode(type.getName().getLocalName(), targetClass.getName());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    String bits = binaryToBitString(data, offset, length);
    if (targetClass == String.class) {
      return (T) bits;
    }
    if (targetClass == Boolean.class) {
      return (T) Boolean.valueOf(BooleanTypeUtil.fromString(bits));
    }
    if (targetClass == PGobject.class || targetClass == Object.class) {
      return (T) toPGobject(type, bits);
    }
    throw Exceptions.cannotDecode(type.getName().getLocalName(), targetClass.getName());
  }

  // ----------------------------- encode -----------------------------

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return toBitString(value, type);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return bitStringToBinary(toBitString(value, type));
  }

  private static String toBitString(Object value, TypeDescriptor type) throws SQLException {
    if (value instanceof Boolean) {
      return (Boolean) value ? "1" : "0";
    }
    String bits;
    if (value instanceof PGobject) {
      String v = ((PGobject) value).getValue();
      bits = v != null ? v : "";
    } else if (value instanceof String) {
      bits = (String) value;
    } else {
      throw Exceptions.cannotEncode(value, "bit");
    }
    requireBitString(bits, type.getName().getLocalName());
    return bits;
  }

  // ------------------------ binary <-> bit string ------------------------

  private static String binaryToBitString(byte[] data, int offset, int length) throws SQLException {
    if (length < 4) {
      throw Exceptions.invalidBinaryLength("bit", length);
    }
    int nbits = ByteConverter.int4(data, offset);
    // The wire form is a 4-byte bit count followed by ceil(nbits/8) packed bytes. Validate the count
    // against the bytes actually present before allocating the StringBuilder or walking the packed
    // body: a negative or oversized count read from untrusted or corrupt wire would otherwise drive an
    // OutOfMemoryError on the allocation or an ArrayIndexOutOfBoundsException in the loop. The server
    // never emits a mismatched length, so reject it with DATA_ERROR. The ceil is computed in long to
    // avoid the (nbits + 7) overflow near Integer.MAX_VALUE.
    long expectedBytes = 4L + (nbits + 7L) / 8L;
    if (nbits < 0 || expectedBytes != length) {
      throw Exceptions.invalidBitCount(nbits, length);
    }
    StringBuilder sb = new StringBuilder(nbits);
    for (int i = 0; i < nbits; i++) {
      int b = data[offset + 4 + (i >> 3)];
      sb.append(((b >> (7 - (i & 7))) & 1) == 1 ? '1' : '0');
    }
    return sb.toString();
  }

  private static byte[] bitStringToBinary(String bits) {
    int nbits = bits.length();
    byte[] out = new byte[4 + (nbits + 7) / 8];
    ByteConverter.int4(out, 0, nbits);
    for (int i = 0; i < nbits; i++) {
      if (bits.charAt(i) == '1') {
        out[4 + (i >> 3)] |= (byte) (1 << (7 - (i & 7)));
      }
    }
    return out;
  }
}
