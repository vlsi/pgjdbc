/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;

/**
 * Factory for the errors thrown by the SQLData/SQLInput/SQLOutput adapters, the codec-context
 * glue, and the setObject/array coercion helpers in this package.
 *
 * <p>Every {@code new PSQLException}/{@code new SQLException}/{@code GT.tr} call in this package
 * lives here so message text and {@link PSQLState} choices stay in one place.</p>
 */
class Exceptions {
  private Exceptions() {
  }

  // Errors from parsing the binary composite wire format are not here: PgSQLInputBinary shares them
  // with the composite codec in another package, so they live in util.internal.CompositeWireErrors.

  // SQLData composite read/write (PgSQLInput/PgSQLOutput).

  static SQLException offlineCompositeAccessNeedsAttributes(String typeFullName) {
    return new PSQLException(
        GT.tr("Offline composite access for {0} needs its attributes; register the type with its "
            + "fields in the offline codec context.", typeFullName),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  static SQLException attemptReadPastEndOfFields() {
    return new PSQLException(
        GT.tr("Attempt to read past end of composite type fields"), PSQLState.DATA_ERROR);
  }

  static SQLException attemptWritePastEndOfFields() {
    return new PSQLException(
        GT.tr("Attempt to write past end of composite type fields"), PSQLState.DATA_ERROR);
  }

  static SQLException compositeAttributeCountWrittenMismatch(String typeFullName, int expected, int actual) {
    return new PSQLException(
        GT.tr("Composite type {0} expects {1} attribute(s), but {2} were written",
            typeFullName, expected, actual),
        PSQLState.DATA_ERROR);
  }

  static SQLException ioErrorReadingStream(Throwable cause) {
    return new PSQLException(
        GT.tr("An I/O error occurred while reading the stream."), PSQLState.IO_ERROR, cause);
  }

  static SQLException notImplemented(String methodSignature) {
    return new PSQLException(GT.tr("{0} not implemented", methodSignature), PSQLState.NOT_IMPLEMENTED);
  }

  static SQLException invalidUrl(String data, Throwable cause) {
    return new PSQLException(GT.tr("Invalid URL: {0}", data), PSQLState.DATA_ERROR, cause);
  }

  // PgCodecContext.

  static SQLException withTypeMapNotSupportedConnectionless() {
    return new SQLException("withTypeMap is not supported on a connectionless PgCodecContext");
  }

  static SQLException cannotDecodeOffline(String typeFullName) {
    return new PSQLException(
        GT.tr("Cannot decode {0} without a database connection. Offline (connectionless) encoding "
            + "and decoding currently supports scalar and temporal types; container types such as "
            + "arrays and composites still require an active connection.", typeFullName),
        PSQLState.NOT_IMPLEMENTED);
  }

  static SQLException noOfflineTypeDescriptor(int oid) {
    return new PSQLException(
        GT.tr("This offline codec context has no type descriptor for OID {0}. Register it through "
            + "the offline builder, or resolve the type on a live connection.", String.valueOf(oid)),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  static SQLException noCodecRegistry(int oid) {
    return new PSQLException(
        GT.tr("This codec context has no codec registry, so it cannot resolve a codec for OID {0}.",
            String.valueOf(oid)),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  // TypeCoercion (setObject / array element coercion).

  static SQLException cannotCast(Object in, String toType) {
    return cannotCast(in, toType, null);
  }

  static SQLException cannotCast(Object in, String toType, @Nullable Throwable cause) {
    return new PSQLException(
        GT.tr("Cannot convert an instance of {0} to type {1}", in.getClass().getName(), toType),
        PSQLState.INVALID_PARAMETER_TYPE, cause);
  }

  // PgStruct.

  static SQLException cannotConvertNestedStructWithoutConnection() {
    return new PSQLException(
        GT.tr("Cannot convert nested struct to SQLData without connection context"),
        PSQLState.OBJECT_NOT_IN_STATE);
  }

  // CodecDepth.

  static SQLException maxNestingDepthExceeded(int maxDepth) {
    return new PSQLException(
        GT.tr("Maximum type nesting depth exceeded: {0}", maxDepth), PSQLState.DATA_ERROR);
  }
}
