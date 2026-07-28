/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.Charset;
import java.sql.SQLException;
import java.util.TimeZone;

/**
 * Per-operation settings a codec needs to encode or decode a value: wire encoding, the time zones
 * that drive temporal conversion, and the {@code getObject} type preferences.
 *
 * <p>A context is immutable and is supplied to every codec call. The surface here is the read-only
 * state a codec consumes; it deliberately exposes no connection, type cache, or codec registry, so
 * a codec written against this class does not depend on the driver's internals.</p>
 *
 * <p><strong>Implemented by the driver.</strong> Applications receive instances — from the codec
 * calls they implement, or from {@code OfflineCodecs.builder()} — but must not subclass this type.
 * It is a class rather than an interface so that the driver can add a method with a working
 * implementation instead of breaking every subclass; that only holds while no application subclasses
 * it. To vary what a context resolves, build one through {@link CodecContextBuilder}.</p>
 *
 * <p>The {@code protected} constructor is a signal, not a barrier: it cannot be package-private
 * because the driver's own implementation lives in another package.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public abstract class CodecContext {

  /**
   * Constructor for the driver's own implementation. Not for application use — see the class
   * javadoc.
   */
  protected CodecContext() {
  }

  /**
   * Returns the connection's character set, used to encode and decode text values.
   *
   * @return the character set
   */
  public abstract Charset getCharset();

  /**
   * Returns whether the backend encodes binary {@code time}/{@code timestamp} payloads as 64-bit
   * integers rather than doubles — the server's {@code integer_datetimes} setting, which every
   * supported release has on. Temporal codecs read this to decode the binary form.
   *
   * @return true if the backend uses integer datetimes
   */
  public abstract boolean usesIntegerDateTimes();

  /**
   * Returns the client/session time zone (the backend's {@code TimeZone} setting). Temporal codecs
   * use it to render binary {@code timetz}/{@code timestamptz} values the way text mode does.
   *
   * @return the client time zone
   */
  public abstract TimeZone getClientTimeZone();

  /**
   * Returns the JVM default time zone, used for {@code date}/{@code time}/{@code timestamp} (without
   * time zone) when no caller time zone is supplied.
   *
   * @return the default time zone
   */
  public abstract TimeZone getDefaultTimeZone();

  /**
   * Returns the time zone of the {@code java.util.Calendar} a caller passed to
   * {@code getDate/getTime/getTimestamp(col, Calendar)}, or {@code null} when none was supplied and
   * {@link #getDefaultTimeZone()} applies.
   *
   * <p>Only the zone crosses this boundary. That is all the driver's own temporal decoding takes
   * from a caller's {@code Calendar}, and handing out the {@code Calendar} itself would publish a
   * mutable object shared with the caller.</p>
   *
   * @return the caller-supplied time zone, or null
   */
  public abstract @Nullable TimeZone getCallerTimeZone();

  /**
   * Returns the per-type {@code getObject} java.time preferences. A set flag makes
   * {@code decode(..., Object.class)} on that temporal type yield the java.time class rather than
   * the {@code java.sql} one; {@link JavaTimePreferences#NONE} means every type yields {@code java.sql}.
   *
   * @return the java.time preferences, never null
   */
  public abstract JavaTimePreferences getJavaTimePreferences();

  /**
   * Returns whether numeric getters on a BOOL column convert {@code 't'}/{@code 'f'} (or binary
   * {@code 0}/{@code 1}) to {@code 1}/{@code 0} instead of throwing. Set by the
   * {@code convertBooleanToNumeric} connection property, or by
   * {@link CodecContextBuilder#convertsBooleanToNumeric(boolean)} offline.
   *
   * @return true if BOOL-to-numeric conversion is enabled
   */
  public abstract boolean convertsBooleanToNumeric();

  /**
   * Returns the {@code IntervalStyle} the interval codec renders a binary {@code interval} with, so
   * that {@code getString} matches what the server would print in text mode and is independent of
   * the wire format.
   *
   * <p>A connection-bound context reports the backend's setting (a GUC_REPORT parameter). Offline
   * there is no server to ask, so it reports whatever
   * {@link CodecContextBuilder#intervalStyle(IntervalStyle)} was given. Either way the fallback is
   * {@link IntervalStyle#POSTGRES}, the server default, when nothing else is known.
   *
   * @return the current interval style, never null
   */
  public IntervalStyle getIntervalStyle() {
    return IntervalStyle.POSTGRES;
  }

  /**
   * Resolves a child type by OID so a container codec (array, composite, domain, range) can decode
   * its elements without reaching into the driver's type cache.
   *
   * <p>The returned descriptor is self-contained: composite attributes and the range subtype
   * ({@code pg_range.rngsubtype}, which {@code typelem} does not carry) are loaded so that
   * {@link TypeDescriptor#getAttributes()} and {@link TypeDescriptor#getRangeSubtype()} are populated.
   * Unknown OIDs resolve to a descriptor for the unknown type rather than null.</p>
   *
   * @param oid the PostgreSQL type OID
   * @return the resolved type descriptor
   * @throws SQLException if the type metadata cannot be loaded
   */
  public abstract TypeDescriptor resolveType(int oid) throws SQLException;

  /**
   * Resolves a type by OID and stamps the given applied modifier onto the descriptor, so a codec can
   * decode a modifier-sensitive value such as {@code numeric(10,2)}. Equivalent to
   * {@code resolveType(oid).withTypmod(typmod)}.
   *
   * <p>An offline or {@code COPY} caller uses this to supply a column or attribute modifier that no
   * {@code RowDescription} provides: {@code ctx.resolveType(oid, typmod)}.</p>
   *
   * @param oid the PostgreSQL type OID
   * @param typmod the applied type modifier, or {@code -1} for none
   * @return the resolved descriptor reporting {@code typmod} from {@link TypeDescriptor#getAppliedTypmod()}
   * @throws SQLException if the type metadata cannot be loaded
   */
  public TypeDescriptor resolveType(int oid, int typmod) throws SQLException {
    return resolveType(oid).withTypmod(typmod);
  }

  /**
   * Resolves the codec registered for a child type by OID. Returns the fallback codec for an
   * unknown OID, so the result is never null.
   *
   * @param oid the PostgreSQL type OID
   * @return the codec for the type
   * @throws SQLException if the type metadata cannot be loaded
   */
  public abstract Codec resolveCodec(int oid) throws SQLException;

  /**
   * Resolves the binary codec for a child type by OID, or null when the registered codec does not
   * support the binary wire format.
   *
   * @param oid the PostgreSQL type OID
   * @return the binary codec, or null if the type has no binary codec
   * @throws SQLException if the type metadata cannot be loaded
   */
  public @Nullable BinaryCodec resolveBinaryCodec(int oid) throws SQLException {
    Codec codec = resolveCodec(oid);
    return codec instanceof BinaryCodec ? (BinaryCodec) codec : null;
  }

  /**
   * Resolves the text codec for a child type by OID, or null when the registered codec does not
   * support the text wire format.
   *
   * @param oid the PostgreSQL type OID
   * @return the text codec, or null if the type has no text codec
   * @throws SQLException if the type metadata cannot be loaded
   */
  public @Nullable TextCodec resolveTextCodec(int oid) throws SQLException {
    Codec codec = resolveCodec(oid);
    return codec instanceof TextCodec ? (TextCodec) codec : null;
  }

  /**
   * Returns the factory for the JDBC objects a container codec hands back or reads through
   * ({@link java.sql.Struct}, {@link java.sql.Array}, and the {@code SQLData} adapters).
   *
   * @return the value factory
   */
  public abstract CodecValueFactory getValueFactory();

  /**
   * Returns a context with the {@code getObject} java.time preferences cleared, so temporal codecs
   * yield the {@code java.sql} types ({@code Date}/{@code Time}/{@code Timestamp}) rather than
   * {@code LocalDate}/{@code LocalTime}/… The array codec uses it when decoding temporal array
   * elements, which return the SQL types regardless of the per-{@code getObject} preferences.
   *
   * @return a context that decodes temporal values as the {@code java.sql} types, or {@code this}
   *     when no preference is set
   */
  public abstract CodecContext withoutJavaTimePreferences();
}
