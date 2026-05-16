/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.CodecContext;
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
 * Specialized codec for the {@code _int4} (INTEGER[]) array type.
 *
 * <p>POC for migrating away from {@link org.postgresql.jdbc.ArrayEncoding} /
 * {@link org.postgresql.jdbc.ArrayDecoding}. This class is intentionally tiny:
 * it owns only the {@code int4} leaf-level strategies (typed direct loop over
 * {@code int[]} / {@code Integer[]}) and delegates all multi-dimensional and
 * header logic to {@link MultiDimArrayBinary} / {@link MultiDimArrayText}.
 * The same helpers serve the generic composite/SQLData path in
 * {@link ArrayCodec}.</p>
 *
 * <p>{@link #decodeBinary} / {@link #decodeText} return a lazy {@link PgArray}
 * for back-compat; the typed fast unpack lives in {@link #decodeBinaryAs}.</p>
 */
public final class Int4ArrayCodec implements BinaryCodec, TextCodec {

  public static final Int4ArrayCodec INSTANCE = new Int4ArrayCodec();

  /** Leaf-level binary writer: direct typed cast, no reflection in hot loop. */
  private static final MultiDimArrayBinary.LeafBinaryWriter BINARY_WRITER =
      new MultiDimArrayBinary.LeafBinaryWriter() {
        @Override
        public void writeLeaf(Object leaf, ByteArrayOutputStream out, byte[] buf)
            throws IOException, SQLException {
          if (leaf instanceof int[]) {
            int[] arr = (int[]) leaf;
            for (int v : arr) {
              ByteConverter.int4(buf, 0, 4);
              out.write(buf);
              ByteConverter.int4(buf, 0, v);
              out.write(buf);
            }
          } else if (leaf instanceof Object[]) {
            Object[] arr = (Object[]) leaf;
            for (Object element : arr) {
              if (element == null) {
                ByteConverter.int4(buf, 0, -1);
                out.write(buf);
              } else {
                ByteConverter.int4(buf, 0, 4);
                out.write(buf);
                ByteConverter.int4(buf, 0, toInt(element));
                out.write(buf);
              }
            }
          } else {
            throw unsupportedLeaf(leaf);
          }
        }

        @Override
        public boolean containsNulls(Object leaf) {
          if (leaf instanceof Object[]) {
            for (Object element : (Object[]) leaf) {
              if (element == null) {
                return true;
              }
            }
          }
          return false;
        }
      };

  /** Leaf-level binary reader: direct typed cast, no reflection in hot loop. */
  private static final MultiDimArrayBinary.LeafBinaryReader BINARY_READER =
      new MultiDimArrayBinary.LeafBinaryReader() {
        @Override
        public void readLeaf(byte[] data, int[] cursor, Object leaf) throws SQLException {
          int pos = cursor[0];
          if (leaf instanceof int[]) {
            int[] arr = (int[]) leaf;
            for (int i = 0; i < arr.length; i++) {
              int len = ByteConverter.int4(data, pos);
              pos += 4;
              if (len == -1) {
                throw new PSQLException(
                    GT.tr("Cannot decode NULL into primitive int[] leaf"),
                    PSQLState.DATA_ERROR);
              }
              arr[i] = ByteConverter.int4(data, pos);
              pos += 4;
            }
          } else if (leaf instanceof Integer[]) {
            Integer[] arr = (Integer[]) leaf;
            for (int i = 0; i < arr.length; i++) {
              int len = ByteConverter.int4(data, pos);
              pos += 4;
              if (len == -1) {
                arr[i] = null;
              } else {
                arr[i] = ByteConverter.int4(data, pos);
                pos += 4;
              }
            }
          } else {
            throw unsupportedLeaf(leaf);
          }
          cursor[0] = pos;
        }
      };

  /** Leaf-level text writer: numbers unquoted, NULL literal for null. */
  private static final MultiDimArrayText.LeafTextWriter TEXT_WRITER =
      new MultiDimArrayText.LeafTextWriter() {
        @Override
        public void appendLeaf(StringBuilder sb, Object leaf, char delim) throws SQLException {
          if (leaf instanceof int[]) {
            int[] arr = (int[]) leaf;
            for (int i = 0; i < arr.length; i++) {
              if (i > 0) {
                sb.append(delim);
              }
              sb.append(arr[i]);
            }
          } else if (leaf instanceof Object[]) {
            Object[] arr = (Object[]) leaf;
            for (int i = 0; i < arr.length; i++) {
              if (i > 0) {
                sb.append(delim);
              }
              if (arr[i] == null) {
                sb.append("NULL");
              } else {
                sb.append(toInt(arr[i]));
              }
            }
          } else {
            throw unsupportedLeaf(leaf);
          }
        }
      };

  private Int4ArrayCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "_int4";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Integer[].class;
  }

  // ---------------------------- encode ----------------------------

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    Object javaArray = unwrap(value);
    if (javaArray == null) {
      // Wrapping Array/PgArray of SQL NULL — emit the zero-dim header.
      byte[] bytes = new byte[12];
      ByteConverter.int4(bytes, 8, Oid.INT4);
      return bytes;
    }
    return MultiDimArrayBinary.encode(javaArray, Oid.INT4, BINARY_WRITER);
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    if (value instanceof Array) {
      String str = value.toString();
      return str != null ? str : "NULL";
    }
    if (!value.getClass().isArray()) {
      throw new PSQLException(
          GT.tr("Cannot convert {0} to _int4 array", value.getClass().getName()),
          PSQLState.INVALID_PARAMETER_TYPE);
    }
    return MultiDimArrayText.encode(value, ',', TEXT_WRITER);
  }

  // ---------------------------- decode ----------------------------

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return new PgArray(ctx.getConnection(), type.getOid(), data);
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    return new PgArray(ctx.getConnection(), type.getOid(), data);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == Array.class || targetClass == PgArray.class || targetClass == Object.class) {
      return (T) decodeBinary(data, type, ctx);
    }
    if (targetClass.isArray()) {
      Class<?> leaf = leafComponentType(targetClass);
      if (leaf == int.class || leaf == Integer.class) {
        return (T) MultiDimArrayBinary.decode(data, leaf, BINARY_READER);
      }
    }
    throw new PSQLException(
        GT.tr("Cannot convert _int4 array to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == Array.class || targetClass == PgArray.class || targetClass == Object.class) {
      return (T) decodeText(data, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) data;
    }
    PgArray pgArray = new PgArray(ctx.getConnection(), type.getOid(), data);
    Object javaArray = pgArray.getArray();
    if (javaArray == null) {
      return null;
    }
    if (targetClass.isInstance(javaArray)) {
      return targetClass.cast(javaArray);
    }
    throw new PSQLException(
        GT.tr("Cannot convert _int4 array to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  // ---------------------------- helpers ----------------------------

  private static @Nullable Object unwrap(Object value) throws SQLException {
    if (value instanceof PgArray) {
      return ((PgArray) value).getArray();
    }
    if (value instanceof Array) {
      return ((Array) value).getArray();
    }
    if (value.getClass().isArray()) {
      return value;
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to _int4 array", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  private static Class<?> leafComponentType(Class<?> arrayClass) {
    Class<?> c = arrayClass;
    while (c.isArray()) {
      c = c.getComponentType();
    }
    return c;
  }

  private static int toInt(Object value) throws SQLException {
    if (value instanceof Integer) {
      return (Integer) value;
    }
    if (value instanceof Number) {
      long asLong = ((Number) value).longValue();
      if (asLong < Integer.MIN_VALUE || asLong > Integer.MAX_VALUE) {
        throw new PSQLException(
            GT.tr("Value {0} is out of int4 range", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (int) asLong;
    }
    if (value instanceof String) {
      try {
        return Integer.parseInt(((String) value).trim());
      } catch (NumberFormatException e) {
        throw new PSQLException(
            GT.tr("Cannot convert value to int4 array element: {0}", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
      }
    }
    if (value instanceof Boolean) {
      return ((Boolean) value) ? 1 : 0;
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to int4 array element", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  private static PSQLException unsupportedLeaf(Object leaf) {
    return new PSQLException(
        GT.tr("Unsupported leaf array class for _int4: {0}", leaf.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }
}
