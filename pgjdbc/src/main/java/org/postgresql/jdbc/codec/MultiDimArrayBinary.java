/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Shared multi-dimensional binary array encoder/decoder.
 *
 * <p>Owns the PostgreSQL array binary wire format:</p>
 * <ul>
 *   <li>{@code int4} dimensions</li>
 *   <li>{@code int4} hasNulls flag</li>
 *   <li>{@code int4} element OID</li>
 *   <li>For each dimension: {@code int4} length + {@code int4} lower bound</li>
 *   <li>Flat depth-first element body: per element {@code int4} length
 *       (or {@code -1} for NULL) followed by the bytes</li>
 * </ul>
 *
 * <p>Outer dimensions are walked via {@link java.lang.reflect.Array}
 * ({@code get}/{@code getLength}/{@code newInstance}) — cost is bounded by the
 * outer-dimension product, not by element count. The leaf level is delegated
 * to caller-provided {@link LeafBinaryWriter}/{@link LeafBinaryReader}
 * strategies that operate on a typed Java leaf array (e.g. {@code int[]},
 * {@code Integer[]}, {@code Object[]}), so the hot per-element loop does
 * direct typed access without reflection.</p>
 */
public final class MultiDimArrayBinary {

  private MultiDimArrayBinary() {
    // Utility class
  }

  /**
   * Strategy for emitting one leaf-level 1-D array (the innermost slice of a
   * multi-dim Java array) as PostgreSQL array body bytes.
   */
  public interface LeafBinaryWriter {
    /**
     * Writes the leaf array's elements as per-element {@code int4} length
     * (or {@code -1} for null) followed by the encoded bytes.
     *
     * @param leaf the leaf-level Java array (e.g. {@code int[]}, {@code Object[]})
     * @param out the output stream
     * @param buf a reusable 4-byte scratch buffer
     */
    void writeLeaf(Object leaf, ByteArrayOutputStream out, byte[] buf)
        throws IOException, SQLException;

    /**
     * @return {@code true} if the leaf contains any null element.
     */
    boolean containsNulls(Object leaf);
  }

  /**
   * Strategy for populating one leaf-level 1-D array from the array body
   * bytes.
   */
  public interface LeafBinaryReader {
    /**
     * Reads {@code leaf.length} elements from {@code data} starting at
     * {@code cursor[0]} into the provided leaf array; advances
     * {@code cursor[0]} past the consumed bytes.
     */
    void readLeaf(byte[] data, int[] cursor, Object leaf) throws SQLException;
  }

  // ---------------------------- encode ----------------------------

  public static byte[] encode(Object javaArray, int elementOid, LeafBinaryWriter leaf)
      throws SQLException {
    int dimensions = computeDimensions(javaArray);
    if (dimensions == 0) {
      // Caller passed a non-array; protect against misuse.
      throw new PSQLException(
          GT.tr("MultiDimArrayBinary.encode requires a Java array, got {0}",
              javaArray.getClass().getName()),
          PSQLState.INVALID_PARAMETER_TYPE);
    }
    int[] dimLengths = computeDimensionLengths(javaArray, dimensions);
    boolean hasNulls = anyLeafHasNulls(javaArray, dimensions, leaf);

    ByteArrayOutputStream baos = new ByteArrayOutputStream(estimateInitialCapacity(dimLengths));
    byte[] buf = new byte[4];
    try {
      writeInt4(baos, buf, dimensions);
      writeInt4(baos, buf, hasNulls ? 1 : 0);
      writeInt4(baos, buf, elementOid);
      for (int d = 0; d < dimensions; d++) {
        writeInt4(baos, buf, dimLengths[d]);
        writeInt4(baos, buf, 1); // lower bound
      }
      walkAndEncode(javaArray, dimensions, baos, buf, leaf);
    } catch (IOException e) {
      // ByteArrayOutputStream never throws.
      throw new AssertionError(e);
    }
    return baos.toByteArray();
  }

  private static void walkAndEncode(Object array, int depth, ByteArrayOutputStream baos,
      byte[] buf, LeafBinaryWriter leaf) throws IOException, SQLException {
    if (depth == 1) {
      leaf.writeLeaf(array, baos, buf);
      return;
    }
    int length = java.lang.reflect.Array.getLength(array);
    for (int i = 0; i < length; i++) {
      walkAndEncode(java.lang.reflect.Array.get(array, i), depth - 1, baos, buf, leaf);
    }
  }

  // ---------------------------- decode ----------------------------

  /**
   * Decodes the binary representation into a Java multi-dim array of shape
   * driven by the wire dimensions, with leaf component type
   * {@code leafComponentType} (e.g. {@code int.class}, {@code Integer.class},
   * {@code Object.class}).
   */
  public static Object decode(byte[] data, Class<?> leafComponentType, LeafBinaryReader leaf)
      throws SQLException {
    int[] cursor = {0};
    int dimensions = readInt4(data, cursor);
    boolean hasNulls = readInt4(data, cursor) != 0;
    readInt4(data, cursor); // element OID — caller already knows it

    if (dimensions == 0) {
      return java.lang.reflect.Array.newInstance(leafComponentType, 0);
    }
    if (hasNulls && leafComponentType.isPrimitive()) {
      throw new PSQLException(
          GT.tr("Cannot decode array containing NULL into a primitive {0}[] leaf",
              leafComponentType.getName()),
          PSQLState.DATA_ERROR);
    }
    int[] dimLengths = new int[dimensions];
    for (int d = 0; d < dimensions; d++) {
      dimLengths[d] = readInt4(data, cursor);
      readInt4(data, cursor); // lower bound
    }
    Object result = java.lang.reflect.Array.newInstance(leafComponentType, dimLengths);
    walkAndDecode(data, cursor, result, dimensions, leaf);
    return result;
  }

  private static void walkAndDecode(byte[] data, int[] cursor, Object container, int depth,
      LeafBinaryReader leaf) throws SQLException {
    if (depth == 1) {
      leaf.readLeaf(data, cursor, container);
      return;
    }
    int length = java.lang.reflect.Array.getLength(container);
    for (int i = 0; i < length; i++) {
      walkAndDecode(data, cursor, java.lang.reflect.Array.get(container, i),
          depth - 1, leaf);
    }
  }

  // ---------------------------- helpers ----------------------------

  private static int computeDimensions(Object array) {
    int dims = 0;
    Class<?> cls = array.getClass();
    while (cls.isArray()) {
      dims++;
      cls = cls.getComponentType();
    }
    return dims;
  }

  /**
   * Computes lengths for each dimension by following the {@code [0]} sub-array
   * at each level — PostgreSQL arrays are rectangular, so the first sub-array
   * determines the dimension's length.
   */
  private static int[] computeDimensionLengths(Object array, int dimensions) {
    int[] lengths = new int[dimensions];
    Object cursor = array;
    for (int d = 0; d < dimensions; d++) {
      int len = java.lang.reflect.Array.getLength(cursor);
      lengths[d] = len;
      if (d + 1 < dimensions && len > 0) {
        cursor = java.lang.reflect.Array.get(cursor, 0);
      }
    }
    return lengths;
  }

  private static boolean anyLeafHasNulls(Object array, int depth, LeafBinaryWriter leaf) {
    if (depth == 1) {
      return leaf.containsNulls(array);
    }
    int length = java.lang.reflect.Array.getLength(array);
    for (int i = 0; i < length; i++) {
      if (anyLeafHasNulls(java.lang.reflect.Array.get(array, i), depth - 1, leaf)) {
        return true;
      }
    }
    return false;
  }

  private static int estimateInitialCapacity(int[] dimLengths) {
    long elements = 1;
    for (int len : dimLengths) {
      elements *= Math.max(1, len);
    }
    // Header + (avg 8 bytes per element) — small fixed bound to keep memory predictable.
    long est = 20L + (8L * elements);
    return est > 1 << 20 ? 1 << 20 : (int) Math.max(64, est);
  }

  private static void writeInt4(ByteArrayOutputStream baos, byte[] buf, int v)
      throws IOException {
    ByteConverter.int4(buf, 0, v);
    baos.write(buf);
  }

  private static int readInt4(byte[] data, int[] cursor) {
    int v = ByteConverter.int4(data, cursor[0]);
    cursor[0] += 4;
    return v;
  }
}
