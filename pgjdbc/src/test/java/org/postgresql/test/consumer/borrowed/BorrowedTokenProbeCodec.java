/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.consumer.borrowed;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;

/**
 * A leaf codec that reports the text it was handed, wrapped in angle brackets so a decoded container
 * shows exactly what reached each element.
 *
 * <p>It follows the {@link TextCodec#decodeText} buffer-ownership rule the way a real codec should:
 * the input may be a borrowed view that goes stale as soon as the container reads the next token, so
 * the value is materialized with {@code toString()} before it is returned. Recording into a field
 * would make the codec stateful and the shared registry unusable from concurrent tests; returning
 * the observation as the decoded value keeps it stateless.</p>
 *
 * <p>Registered through the {@code Codec} {@link java.util.ServiceLoader} SPI and driven by
 * {@code BorrowedTokenContractTest}.</p>
 */
public final class BorrowedTokenProbeCodec implements TextCodec {

  /** The {@code pg_type.typname} this codec claims. */
  public static final String TYPE_NAME = "borrowed_token_probe";

  @Override
  public Class<?> getDefaultJavaType() {
    return String.class;
  }

  @Override
  public Object decodeText(CharSequence data, TypeDescriptor type, CodecContext ctx) {
    return "<" + data.toString() + ">";
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) {
    return String.valueOf(value);
  }
}
