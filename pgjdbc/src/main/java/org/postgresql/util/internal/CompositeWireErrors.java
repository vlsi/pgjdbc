/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.SQLException;

/**
 * Errors raised while parsing the binary wire format of a composite (row) value.
 *
 * <p>Two parsers in different packages read that format — {@code CompositeCodec} in
 * {@code org.postgresql.jdbc.codec} and {@code PgSQLInputBinary} in {@code org.postgresql.jdbc} —
 * and a value rejected by one must be rejected the same way by the other. Neither package can see
 * the other's package-private {@code Exceptions} class, so the shared messages and
 * {@link PSQLState} choices live here.</p>
 *
 * <p>Internal to the driver: not part of the public codec API. A codec outside the driver reports
 * conversion failures through {@code Codecs.cannotDecode}/{@code cannotEncode} instead.</p>
 */
public class CompositeWireErrors {

  private CompositeWireErrors() {
  }

  /**
   * The binary composite value was too short to hold a field count.
   *
   * @return decode error, carrying {@link PSQLState#DATA_ERROR}
   */
  public static SQLException tooShort() {
    return new PSQLException(GT.tr("Invalid binary composite data: too short"), PSQLState.DATA_ERROR);
  }

  /**
   * The binary composite value declared a negative field count.
   *
   * @param fieldCount the (negative) declared field count
   * @return decode error, carrying {@link PSQLState#DATA_ERROR}
   */
  public static SQLException negativeFieldCount(int fieldCount) {
    return new PSQLException(
        GT.tr("Invalid binary composite data: negative field count {0}", fieldCount),
        PSQLState.DATA_ERROR);
  }

  /**
   * The declared field count cannot fit in the bytes that remain, so the value is corrupt. Checked
   * before sizing any per-field collection, so a hostile count cannot drive an allocation.
   *
   * @param fieldCount the declared field count
   * @return decode error, carrying {@link PSQLState#DATA_ERROR}
   */
  public static SQLException fieldCountExceedsData(int fieldCount) {
    return new PSQLException(
        GT.tr("Invalid binary composite data: field count {0} exceeds remaining data", fieldCount),
        PSQLState.DATA_ERROR);
  }

  /**
   * The binary composite value ended before its declared field count was satisfied.
   *
   * @param fieldIndex the 0-based field index where the data ran out
   * @return decode error, carrying {@link PSQLState#DATA_ERROR}
   */
  public static SQLException unexpectedEnd(int fieldIndex) {
    return new PSQLException(
        GT.tr("Invalid binary composite data: unexpected end at field {0}", fieldIndex),
        PSQLState.DATA_ERROR);
  }

  /**
   * A composite field declared a negative length.
   *
   * @param length the (negative) declared field length
   * @param fieldIndex the 0-based field index
   * @return decode error, carrying {@link PSQLState#DATA_ERROR}
   */
  public static SQLException fieldLength(int length, int fieldIndex) {
    return new PSQLException(
        GT.tr("Invalid binary composite data: invalid length {0} at field {1}", length, fieldIndex),
        PSQLState.DATA_ERROR);
  }

  /**
   * A composite field's declared length exceeds the remaining data.
   *
   * @param fieldIndex the 0-based field index
   * @return decode error, carrying {@link PSQLState#DATA_ERROR}
   */
  public static SQLException notEnoughData(int fieldIndex) {
    return new PSQLException(
        GT.tr("Invalid binary composite data: not enough data for field {0}", fieldIndex),
        PSQLState.DATA_ERROR);
  }
}
