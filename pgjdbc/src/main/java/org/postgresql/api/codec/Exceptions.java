/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.SQLException;

/**
 * Factory for the errors thrown by this package's own dispatch logic (see {@link Codecs}).
 *
 * <p>{@code cannotDecode}/{@code cannotEncode} live on {@link Codecs} instead, since they are
 * public: codecs outside this package (e.g. {@code org.postgresql.jdbc.codec}, {@code
 * org.postgresql.jdbc}) need to report the same conversion errors and cannot see this
 * package-private class.</p>
 */
class Exceptions {
  private Exceptions() {
  }

  /**
   * Creates the error for a type that has no registered codec in the requested wire format.
   *
   * @param format the format name interpolated into the message, {@code "binary"} or {@code "text"}
   */
  static SQLException noCodecForFormat(TypeDescriptor type, String format) {
    return new PSQLException(
        GT.tr("No {0} codec is registered for type {1}.", format, type.getFormattedName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  /**
   * Creates the error the default primitive-read paths raise for a decoded value the requested
   * numeric target cannot represent.
   *
   * <p>The value either overflows the target's range or is a non-finite {@code NaN}/{@code Infinity}
   * that {@code int}/{@code long}/{@code BigDecimal} cannot hold. The error carries
   * {@link PSQLState#NUMERIC_VALUE_OUT_OF_RANGE}, matching the built-in codecs' own range checks.</p>
   *
   * @param targetType the target type name (for example {@code "int"})
   */
  static SQLException valueOutOfRange(Object value, String targetType) {
    return new PSQLException(
        GT.tr("Value {0} is out of range for {1}", value, targetType),
        PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
  }
}
