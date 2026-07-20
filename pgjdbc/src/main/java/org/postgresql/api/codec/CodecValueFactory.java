/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.Array;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.Struct;

/**
 * Builds the JDBC objects a container codec hands back or reads through: {@link Struct},
 * {@link Array}, and the {@link SQLInput}/{@link java.sql.SQLOutput} adapters that drive
 * {@link SQLData}.
 *
 * <p>Implemented by the driver; applications receive instances but must not implement this type.
 * Obtain one from {@link CodecContext#getValueFactory()}.</p>
 *
 * <p>These live apart from {@link CodecContext} because they are a different kind of thing and are
 * used at a different rate: a context is read many times while decoding one value (its charset and
 * time zone on every field), whereas a factory is called once per composite or array. Keeping them
 * separate leaves the context narrow.</p>
 *
 * <p>Constructing these objects is the driver's job, not a codec's: it is what lets a codec stay
 * free of the driver's concrete {@code PgStruct}/{@code PgArray}/{@code PgSQLInput} classes and
 * work against this contract alone.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public interface CodecValueFactory {

  /**
   * Creates a {@link Struct} for {@code type} over the already-decoded attribute values.
   *
   * <p>{@code literal} is the text the value was decoded from, when the caller has it. The struct
   * outlives this call, so the driver keeps the literal only when doing so costs no copy — that is,
   * when it is already a {@code String}. A borrowed slice, or {@code null} for a binary payload,
   * leaves the struct rebuilding its text from the attributes on demand. Either way the attributes
   * are the same; only whether the text view is the verbatim server output differs.</p>
   *
   * @param type the composite type
   * @param attributes the decoded attribute values, SQL NULL as {@code null}
   * @param literal the composite text literal, or {@code null} when the caller has none; not
   *     retained unless keeping it is free
   * @return the struct
   * @throws SQLException if the struct cannot be created
   */
  Struct createStruct(TypeDescriptor type, @Nullable Object[] attributes,
      @Nullable CharSequence literal) throws SQLException;

  /**
   * Creates an {@link Array} backed by the array's binary wire payload, decoding its elements only
   * as they are read.
   *
   * @param type the array type
   * @param data the binary payload, owned by the returned array
   * @return the lazy array, or {@code null} when no connection backs one and the caller must decode
   *     the payload eagerly instead
   * @throws SQLException if the array cannot be created
   */
  @Nullable Array createArray(TypeDescriptor type, byte[] data) throws SQLException;

  /**
   * Creates an {@link Array} backed by the array's text literal, decoding its elements only as they
   * are read.
   *
   * <p>{@code literal} may be a borrowed view over a larger buffer (a slice of a composite or array
   * literal being decoded). The returned array outlives this call, so the driver copies the
   * characters it needs — the caller never has to materialize a {@code String} up front, and when
   * this returns {@code null} nothing is copied at all.</p>
   *
   * @param type the array type
   * @param literal the array text literal; not retained
   * @return the lazy array, or {@code null} when no connection backs one and the caller must decode
   *     the literal eagerly instead
   * @throws SQLException if the array cannot be created
   */
  @Nullable Array createArray(TypeDescriptor type, CharSequence literal) throws SQLException;

  /**
   * Creates an {@link SQLInput} reading {@code type}'s attributes from a binary composite payload,
   * to drive {@link java.sql.SQLData#readSQL}.
   *
   * @param type the composite type
   * @param data the backing buffer
   * @param offset start of the composite within {@code data}
   * @param length number of bytes for the composite
   * @return the input
   * @throws SQLException if the input cannot be created
   */
  SQLInput createSQLInput(TypeDescriptor type, byte[] data, int offset, int length)
      throws SQLException;

  /**
   * Creates an {@link SQLInput} reading {@code type}'s attributes from a composite text literal, to
   * drive {@link java.sql.SQLData#readSQL}.
   *
   * <p>{@code literal} may be a borrowed view over a larger buffer. The returned input is consumed
   * by the {@code readSQL} call it is passed to and does not outlive it, so nothing here is
   * retained and a caller decoding a nested composite in place needs no {@code String}.</p>
   *
   * @param type the composite type
   * @param literal the composite text literal; not retained
   * @return the input
   * @throws SQLException if the input cannot be created
   */
  SQLInput createSQLInput(TypeDescriptor type, CharSequence literal) throws SQLException;

  /**
   * Writes {@code value} into {@code sink} as {@code type}'s binary composite form, by driving
   * {@link SQLData#writeSQL} against an {@link java.sql.SQLOutput}.
   *
   * <p>The whole write is one call rather than a handed-out {@code SQLOutput} because the composite
   * framing has to be finished after the last attribute — a length back-patch in binary, a closing
   * parenthesis in text. Owning both ends here keeps that off the caller.</p>
   *
   * @param type the composite type
   * @param value the value to write
   * @param sink the sink to write into
   * @throws SQLException if writing fails
   */
  void writeSQLData(TypeDescriptor type, SQLData value, BackpatchingByteArrayOutputStream sink)
      throws SQLException;

  /**
   * Writes {@code value} into {@code out} as {@code type}'s composite text literal, by driving
   * {@link SQLData#writeSQL} against an {@link java.sql.SQLOutput}.
   *
   * @param type the composite type
   * @param value the value to write
   * @param out the target to append the literal to
   * @throws SQLException if writing fails
   */
  void writeSQLData(TypeDescriptor type, SQLData value, Appendable out) throws SQLException;
}
