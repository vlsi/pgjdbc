/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import java.nio.charset.Charset;
import java.util.Map;
import java.util.TimeZone;

/**
 * Builds a connectionless {@link CodecContext} for offline encoding and decoding.
 *
 * <p>Supply the wire settings (charset, time zone, integer-datetime mode), the {@link CodecLookup}
 * that resolves codecs, and descriptors for any child types a container resolves. The result drives
 * {@link Codecs#encode} and {@link Codecs#decode} for scalar and temporal types with no connection;
 * container types still need a live connection.</p>
 *
 * <p>Defaults: UTF-8, UTC, integer datetimes, a fresh registry with the built-in codecs, no
 * {@code getObject} java.time preferences, and no boolean-to-numeric coercion.</p>
 *
 * <p>Obtain a builder from the driver, for example {@code OfflineCodecs.builder()}.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public interface CodecContextBuilder {

  /**
   * Sets the character set for text values. Defaults to UTF-8.
   *
   * @param charset the character set
   * @return this builder
   */
  CodecContextBuilder charset(Charset charset);

  /**
   * Sets the client/session time zone temporal codecs render {@code timetz}/{@code timestamptz}
   * against — what {@link CodecContext#getClientTimeZone()} reports. Defaults to UTC.
   *
   * <p>There is no setter for {@link CodecContext#getJvmDefaultTimeZone()}: it is read from the JVM
   * rather than held as context state.</p>
   *
   * @param clientTimeZone the session time zone
   * @return this builder
   */
  CodecContextBuilder clientTimeZone(TimeZone clientTimeZone);

  /**
   * Sets whether the backend encodes binary {@code time}/{@code timestamp} payloads as 64-bit
   * integers ({@code true}, the modern default) rather than doubles.
   *
   * @param integerDateTimes true for integer datetimes
   * @return this builder
   */
  CodecContextBuilder usesIntegerDateTimes(boolean integerDateTimes);

  /**
   * Sets the codec lookup that resolves codecs by OID and name. Defaults to a fresh registry with
   * the built-in codecs.
   *
   * @param codecLookup the codec lookup
   * @return this builder
   */
  CodecContextBuilder codecLookup(CodecLookup codecLookup);

  /**
   * Registers {@code type} under its own OID so a container can resolve it as a child type.
   *
   * @param type the type descriptor
   * @return this builder
   */
  CodecContextBuilder type(TypeDescriptor type);

  /**
   * Registers every descriptor in {@code types}, keyed by OID.
   *
   * @param types the type descriptors by OID
   * @return this builder
   */
  CodecContextBuilder types(Map<Integer, ? extends TypeDescriptor> types);

  /**
   * Sets the {@code getObject} java.time preferences, matching the per-type connection properties.
   * Each flag makes {@code decode(..., Object.class)} on that type yield the java.time class rather
   * than the {@code java.sql} one.
   *
   * @param preferences the per-type java.time preferences; build one with
   *     {@link JavaTimePreferences#builder()}
   * @return this builder
   */
  CodecContextBuilder javaTimePreferences(JavaTimePreferences preferences);

  /**
   * Sets the {@code IntervalStyle} the interval codec renders a binary {@code interval} with, so
   * {@code getString} matches what the server would print in text mode. Defaults to
   * {@link IntervalStyle#POSTGRES}, the server default.
   *
   * <p>A connection-bound context reads this from the reported server parameter; offline there is no
   * server to ask, so a caller decoding intervals produced under a different setting supplies it
   * here.</p>
   *
   * @param intervalStyle the interval style
   * @return this builder
   */
  CodecContextBuilder intervalStyle(IntervalStyle intervalStyle);

  /**
   * Sets whether numeric getters on a {@code bool} value coerce it to {@code 1}/{@code 0} instead of
   * throwing.
   *
   * @param convertBooleanToNumeric true to enable the coercion
   * @return this builder
   */
  CodecContextBuilder convertsBooleanToNumeric(boolean convertBooleanToNumeric);

  /**
   * Builds the connectionless context.
   *
   * @return a {@link CodecContext} that encodes and decodes without a connection
   */
  CodecContext build();
}
