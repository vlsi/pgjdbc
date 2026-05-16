/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.core.BaseConnection;
import org.postgresql.jdbc.ArrayDecoding;
import org.postgresql.jdbc.ArrayEncoding;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.CodecDepth;
import org.postgresql.jdbc.PgArray;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Array;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL array types.
 *
 * <p>This codec handles encoding and decoding of PostgreSQL arrays by delegating
 * to {@link ArrayEncoding} and {@link ArrayDecoding} utilities. For decoding,
 * it returns a {@link PgArray} that lazily decodes elements on access. For encoding,
 * it accepts both {@link Array} (PgArray) and raw Java array objects.</p>
 */
public final class ArrayCodec implements BinaryCodec, TextCodec {

  public static final ArrayCodec INSTANCE = new ArrayCodec();

  private ArrayCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "array";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Array.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    // Return a PgArray wrapping the binary data for lazy decoding
    BaseConnection conn = ctx.getConnection();
    return new PgArray(conn, type.getOid(), data);
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    if (value instanceof PgArray) {
      PgArray pgArray = (PgArray) value;
      if (pgArray.isBinary()) {
        byte[] bytes = pgArray.toBytes();
        if (bytes != null) {
          return bytes;
        }
      }
      // Text-mode PgArray: decode to Java array, then re-encode as binary
      Object javaArray = pgArray.getArray();
      if (javaArray != null) {
        return encodeBinaryJavaArray(javaArray, type, ctx);
      }
      return new byte[0];
    }
    if (value instanceof Array) {
      // Generic JDBC Array - get the underlying array and encode
      Object javaArray = ((Array) value).getArray();
      if (javaArray != null) {
        return encodeBinaryJavaArray(javaArray, type, ctx);
      }
      return new byte[0];
    }
    if (value.getClass().isArray()) {
      return encodeBinaryJavaArray(value, type, ctx);
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to array", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @SuppressWarnings("unchecked")
  private byte[] encodeBinaryJavaArray(Object javaArray, PgType type, CodecContext ctx) throws SQLException {
    int arrayOid = type.getOid();
    if (ArrayEncoding.hasNativeEncoder(javaArray)) {
      ArrayEncoding.ArrayEncoder<Object> encoder = ArrayEncoding.getArrayEncoder(javaArray);
      if (encoder.supportBinaryRepresentation(arrayOid)) {
        return encoder.toBinaryRepresentation(ctx.getConnection(), javaArray, arrayOid);
      }
    }
    if (!(javaArray instanceof Object[])) {
      // No native encoder and not an Object[] (e.g. a primitive multi-dim array
      // whose oid we do not support binary-wise). Defer to ArrayEncoding so it
      // produces the same error message it always has.
      ArrayEncoding.ArrayEncoder<Object> encoder = ArrayEncoding.getArrayEncoder(javaArray);
      return encoder.toBinaryRepresentation(ctx.getConnection(), javaArray, arrayOid);
    }
    return encodeBinaryArrayViaCodec(javaArray, type, ctx);
  }

  /**
   * Encodes a generic {@code Object[]} (any rank) by dispatching each leaf
   * element through the registered binary codec for the array's element type.
   * Used for element types that {@link ArrayEncoding} cannot encode natively
   * (composite, SQLData, domain over composite, etc.). Multi-dim header /
   * dimension walking is shared with {@link Int4ArrayCodec} via
   * {@link MultiDimArrayBinary}.
   */
  private byte[] encodeBinaryArrayViaCodec(Object javaArray, PgType arrayType, CodecContext ctx)
      throws SQLException {
    int elementOid = arrayType.getTypelem();
    PgType elementType = ctx.getTypeInfo().getPgTypeByOid(elementOid);
    BinaryCodec elementCodec = ctx.getCodecs().getBinaryCodec(elementOid, elementType);
    if (elementCodec == null) {
      throw new PSQLException(
          GT.tr("No binary codec registered for array element oid {0}", elementOid),
          PSQLState.INVALID_PARAMETER_TYPE);
    }
    MultiDimArrayBinary.LeafBinaryWriter leafWriter = new MultiDimArrayBinary.LeafBinaryWriter() {
      @Override
      public void writeLeaf(Object leaf, ByteArrayOutputStream out, byte[] buf)
          throws IOException, SQLException {
        Object[] arr = (Object[]) leaf;
        for (Object element : arr) {
          if (element == null) {
            ByteConverter.int4(buf, 0, -1);
            out.write(buf);
          } else {
            byte[] encoded = elementCodec.encodeBinary(element, elementType, ctx);
            ByteConverter.int4(buf, 0, encoded.length);
            out.write(buf);
            out.write(encoded);
          }
        }
      }

      @Override
      public boolean containsNulls(Object leaf) {
        for (Object element : (Object[]) leaf) {
          if (element == null) {
            return true;
          }
        }
        return false;
      }
    };
    return MultiDimArrayBinary.encode(javaArray, elementOid, leafWriter);
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    // Return a PgArray wrapping the text data for lazy decoding
    BaseConnection conn = ctx.getConnection();
    return new PgArray(conn, type.getOid(), data);
  }

  @Override
  @SuppressWarnings("unchecked")
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    if (value instanceof Array) {
      String str = value.toString();
      return str != null ? str : "NULL";
    }
    if (value.getClass().isArray()) {
      if (ArrayEncoding.hasNativeEncoder(value)) {
        ArrayEncoding.ArrayEncoder<Object> encoder = ArrayEncoding.getArrayEncoder(value);
        return encoder.toArrayString(type.getDelimiter(), value);
      }
      if (value instanceof Object[]) {
        return encodeTextArrayViaCodec(value, type, ctx);
      }
      // No native encoder and not an Object[]; let ArrayEncoding produce its
      // standard error.
      ArrayEncoding.ArrayEncoder<Object> encoder = ArrayEncoding.getArrayEncoder(value);
      return encoder.toArrayString(type.getDelimiter(), value);
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to array", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  /**
   * Encodes a generic {@code Object[]} (any rank) as a PostgreSQL text array
   * literal, dispatching each leaf element through the registered text codec
   * for the array's element type. Multi-dim brace walking is shared with
   * {@link Int4ArrayCodec} via {@link MultiDimArrayText}.
   */
  private String encodeTextArrayViaCodec(Object javaArray, PgType arrayType, CodecContext ctx)
      throws SQLException {
    int elementOid = arrayType.getTypelem();
    PgType elementType = ctx.getTypeInfo().getPgTypeByOid(elementOid);
    TextCodec elementCodec = ctx.getCodecs().getTextCodec(elementOid, elementType);
    if (elementCodec == null) {
      throw new PSQLException(
          GT.tr("No text codec registered for array element oid {0}", elementOid),
          PSQLState.INVALID_PARAMETER_TYPE);
    }
    MultiDimArrayText.LeafTextWriter leafWriter = (sb, leaf, delim) -> {
      Object[] arr = (Object[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          sb.append(delim);
        }
        Object element = arr[i];
        if (element == null) {
          sb.append("NULL");
        } else {
          String elementText = elementCodec.encodeText(element, elementType, ctx);
          PgArray.escapeArrayElement(sb, elementText);
        }
      }
    };
    return MultiDimArrayText.encode(javaArray, arrayType.getDelimiter(), leafWriter);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == Array.class || targetClass == PgArray.class || targetClass == Object.class) {
      return (T) decodeBinary(data, type, ctx);
    }
    if (targetClass.isArray()) {
      // Decode binary array to Java array, then the caller gets the typed array
      CodecDepth.enter();
      try {
        BaseConnection conn = ctx.getConnection();
        return (T) ArrayDecoding.readBinaryArray(1, 0, data, conn);
      } finally {
        CodecDepth.exit();
      }
    }
    throw new PSQLException(
        GT.tr("Cannot convert array to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == Array.class || targetClass == PgArray.class || targetClass == Object.class) {
      return (T) decodeText(data, type, ctx);
    }
    if (targetClass.isArray()) {
      // Decode text array to Java array
      CodecDepth.enter();
      try {
        BaseConnection conn = ctx.getConnection();
        ArrayDecoding.PgArrayList arrayList = ArrayDecoding.buildArrayList(data, type.getDelimiter());
        return (T) ArrayDecoding.readStringArray(1, arrayList.size(), type.getTypelem(), arrayList, conn);
      } finally {
        CodecDepth.exit();
      }
    }
    if (targetClass == String.class) {
      return (T) data;
    }
    throw new PSQLException(
        GT.tr("Cannot convert array to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public @Nullable String decodeAsString(String data, PgType type, CodecContext ctx) throws SQLException {
    // Preserve the PostgreSQL text representation (e.g. {{1,0},{0,1}});
    // PgArray.toString() would re-emit elements with quotes.
    return data;
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw cannotConvert("int");
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    throw cannotConvert("int");
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw cannotConvert("long");
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    throw cannotConvert("long");
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw cannotConvert("double");
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    throw cannotConvert("double");
  }

  private static PSQLException cannotConvert(String targetType) {
    return new PSQLException(
        GT.tr("Cannot convert array to {0}", targetType),
        PSQLState.INVALID_PARAMETER_TYPE);
  }
}
