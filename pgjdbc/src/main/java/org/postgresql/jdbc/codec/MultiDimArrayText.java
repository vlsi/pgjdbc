/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.SQLException;

/**
 * Shared multi-dimensional text array encoder.
 *
 * <p>Owns the PostgreSQL text array literal format ({@code {a,b,{c,d}}}).
 * Outer dimensions are walked via {@link java.lang.reflect.Array}; the leaf
 * level is delegated to a caller-provided {@link LeafTextWriter} that
 * appends one 1-D slice's elements (without surrounding braces).</p>
 */
public final class MultiDimArrayText {

  private MultiDimArrayText() {
    // Utility class
  }

  /**
   * Strategy for appending one leaf-level 1-D array as a comma-delimited
   * sequence of element literals (no surrounding braces — the helper adds
   * them).
   */
  public interface LeafTextWriter {
    void appendLeaf(StringBuilder sb, Object leaf, char delim) throws SQLException;
  }

  public static String encode(Object javaArray, char delim, LeafTextWriter leaf)
      throws SQLException {
    int dimensions = computeDimensions(javaArray);
    if (dimensions == 0) {
      throw new PSQLException(
          GT.tr("MultiDimArrayText.encode requires a Java array, got {0}",
              javaArray.getClass().getName()),
          PSQLState.INVALID_PARAMETER_TYPE);
    }
    StringBuilder sb = new StringBuilder(128);
    walk(sb, javaArray, dimensions, delim, leaf);
    return sb.toString();
  }

  private static void walk(StringBuilder sb, Object array, int depth, char delim,
      LeafTextWriter leaf) throws SQLException {
    if (depth == 1) {
      sb.append('{');
      leaf.appendLeaf(sb, array, delim);
      sb.append('}');
      return;
    }
    sb.append('{');
    int length = java.lang.reflect.Array.getLength(array);
    for (int i = 0; i < length; i++) {
      if (i > 0) {
        sb.append(delim);
      }
      walk(sb, java.lang.reflect.Array.get(array, i), depth - 1, delim, leaf);
    }
    sb.append('}');
  }

  private static int computeDimensions(Object array) {
    int dims = 0;
    Class<?> cls = array.getClass();
    while (cls.isArray()) {
      dims++;
      cls = cls.getComponentType();
    }
    return dims;
  }
}
