/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;

/**
 * Encodes a Java value for a PostgreSQL column, keyed by the column's type OID and wire format, over
 * the codec registry.
 *
 * <p>This is the runtime embodiment of the driver's write-coercion model: the outcome depends only
 * on the target OID and the value's Java class, not on which JDBC surface presents it, because every
 * write funnels through the same codec {@code encodeText} / {@code encodeBinary}. The updatable
 * {@code ResultSet} row-buffer refresh uses this entry point; the {@code setObject} bind path and
 * {@code SQLOutput} can adopt it.
 *
 * <p>The resolved codec is never {@code null} in normal operation: {@link CodecRegistry} falls back
 * to {@link org.postgresql.jdbc.codec.FallbackCodec}, which is both a {@code BinaryCodec} and a
 * {@code TextCodec}. The {@code null} guards are defensive -- fabricating bytes in the wrong wire
 * format would corrupt the value, so a missing codec refuses with {@link PSQLState#CANNOT_COERCE}.
 */
final class ColumnValueEncoder {

  private ColumnValueEncoder() {
  }

  /** Encodes {@code value} as the binary wire representation of the column with type {@code oid}. */
  static byte[] encodeBinary(Object value, int oid, PgType pgType, PgCodecContext ctx)
      throws SQLException {
    BinaryCodec codec = ctx.getCodecs().getBinaryCodec(oid, pgType);
    if (codec == null) {
      throw cannotEncode(oid, value);
    }
    return codec.encodeBinary(value, pgType, ctx);
  }

  /**
   * Encodes {@code value} as the text wire representation of the column with type {@code oid}.
   * Returns {@code null} when the codec maps the value to a SQL {@code NULL}.
   */
  static @Nullable String encodeText(Object value, int oid, PgType pgType, PgCodecContext ctx)
      throws SQLException {
    TextCodec codec = ctx.getCodecs().getTextCodec(oid, pgType);
    if (codec == null) {
      throw cannotEncode(oid, value);
    }
    return codec.encodeText(value, pgType, ctx);
  }

  private static PSQLException cannotEncode(int oid, Object value) {
    return new PSQLException(
        GT.tr("Cannot encode a {0} for the column with type OID {1}: "
            + "no codec supports the column''s wire format.",
            value.getClass().getName(), oid),
        PSQLState.CANNOT_COERCE);
  }
}
