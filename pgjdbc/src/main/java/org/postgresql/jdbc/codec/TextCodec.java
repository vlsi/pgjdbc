/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

/**
 * Codec for PostgreSQL text type.
 *
 * <p>Its wire is the charset text in both formats, so it shares {@link AbstractTextCodec}'s logic under the
 * {@code text} type name.</p>
 */
public final class TextCodec extends AbstractTextCodec {

  public static final TextCodec INSTANCE = new TextCodec();

  private TextCodec() {
  }
}
