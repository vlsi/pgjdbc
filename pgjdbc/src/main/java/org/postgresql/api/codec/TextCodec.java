/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;

/**
 * Codec for encoding and decoding PostgreSQL values in text format.
 *
 * <p>Text format is the default wire format for PostgreSQL. While less efficient
 * than binary format, it's universally supported for all types.</p>
 *
 * <p>Implementations must be stateless and thread-safe. All connection-specific
 * settings are provided via {@link CodecContext}.</p>
 *
 * @see BinaryCodec
 * @see CodecContext
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public interface TextCodec extends Codec {

  /**
   * Decodes a value from its text representation.
   *
   * <p>{@code data} is the <em>already-unquoted</em> logical value. When a container codec
   * (array, composite, range) decodes an element, its tokenizer has stripped the surrounding
   * quotes and unescaped the content, so an implementation only parses the value itself and never
   * deals with array or composite syntax.</p>
   *
   * <p><strong>Buffer ownership.</strong> {@code data} may be a mutable view borrowed from a larger buffer, valid only for the
   * duration of this call. An implementation must not retain the reference after it returns, and
   * must not hand it to anything that outlives the call. To keep the text, call
   * {@link CharSequence#toString()} before returning and keep the resulting {@code String}.</p>
   *
   * <p>A {@code String} is an ordinary input here and needs no copy: {@code toString()} on it
   * returns the same instance, so a codec that materializes the text pays nothing extra for a
   * caller that already had one.</p>
   *
   * @param data the text representation (never null)
   * @param type the PostgreSQL type information
   * @param ctx the codec context providing connection settings
   * @return the decoded Java object
   * @throws SQLException if decoding fails
   */
  @Nullable Object decodeText(CharSequence data, TypeDescriptor type, CodecContext ctx)
      throws SQLException;

  /**
   * Encodes a value to text format.
   *
   * @param value the Java object to encode (never null)
   * @param type the PostgreSQL type information
   * @param ctx the codec context providing connection settings
   * @return the text representation
   * @throws SQLException if encoding fails
   */
  String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException;

  /**
   * Whether this codec's {@link #encodeText} output for {@code type} can contain characters that
   * require quoting when embedded in a composite or array literal (a comma, parenthesis, brace,
   * double quote, backslash, leading/trailing whitespace, or the empty string). Numeric and boolean
   * codecs emit only quote-safe characters (digits, sign, dot, {@code e}, {@code t}/{@code f},
   * {@code NaN}, {@code Infinity}) and return {@code false}, letting a container stream such a field
   * straight into the literal without quoting.
   *
   * <p>The {@code type} argument matters for a delegating codec whose output depends on the concrete
   * type it wraps: a domain has no text of its own and renders as its base type, so a domain over
   * {@code int4} is quote-safe while a domain over {@code text} is not — a distinction the shared
   * {@link org.postgresql.jdbc.codec.DomainCodec} singleton can only make once it resolves the base
   * type from {@code type}. A leaf codec whose answer is type-independent ignores the argument. The
   * default is {@code true} — assume quoting may be needed.
   *
   * @param type the concrete type whose value is being embedded in a composite or array literal
   * @param ctx the codec context, used to resolve a delegating codec's underlying type
   * @return true if the text output for {@code type} may need composite/array quoting
   * @throws SQLException if resolving the underlying type fails
   */
  default boolean mayRequireQuoting(TypeDescriptor type, CodecContext ctx) throws SQLException {
    return true;
  }

  /**
   * Whether {@link #decodeText} reads the PostgreSQL text wire format for this type. This is the
   * read-side counterpart to {@link BinaryCodec#decodesBinary()}. Text is the universal receive
   * format, so the default is {@code true} and almost every codec keeps it; a codec that handles
   * only the binary representation returns {@code false}. The capability lets a caller pick a
   * readable format without resorting to {@code instanceof}, which matters for the offline and
   * {@code COPY} paths that have no format negotiation to fall back on.
   *
   * @return true if {@link #decodeText} reads the text representation (the default)
   */
  default boolean decodesText() {
    return true;
  }

  /**
   * Decodes text data as a String value.
   *
   * <p>Default implementation decodes and calls {@code toString()}.</p>
   *
   * @param data the text data
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the string value
   * @throws SQLException if decoding fails
   */
  default @Nullable String decodeAsString(CharSequence data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    Object value = decodeText(data, type, ctx);
    return value == null ? null : value.toString();
  }

  /**
   * Decodes text data into an instance of the specified target class.
   *
   * <p>Codecs implement this method to support conversions to various Java types.
   * For example, a timestamp codec might support conversion to
   * {@code LocalDateTime}, {@code Instant}, {@code OffsetDateTime}, etc.</p>
   *
   * @param data the text data
   * @param type the PostgreSQL type information
   * @param targetClass the desired Java class for the result
   * @param ctx the codec context
   * @param <T> the target type
   * @return the decoded object as the target type
   * @throws SQLException if conversion to the target class is not supported
   */
  default <T> @Nullable T decodeTextAs(CharSequence data, TypeDescriptor type, Class<T> targetClass,
      CodecContext ctx) throws SQLException {
    Object value = decodeText(data, type, ctx);
    if (value == null) {
      return null;
    }
    if (targetClass.isInstance(value)) {
      return targetClass.cast(value);
    }
    throw Codecs.cannotDecode(type.getFormattedName(), targetClass.getName());
  }
}
