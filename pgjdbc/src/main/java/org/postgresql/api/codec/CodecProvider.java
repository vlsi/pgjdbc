/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import java.util.List;

/**
 * Contributes codecs to the driver through {@link java.util.ServiceLoader}.
 *
 * <p>Implement this, list the implementation in
 * {@code META-INF/services/org.postgresql.api.codec.CodecProvider}, and the driver picks up every
 * {@link CodecRegistration} it returns.</p>
 *
 * <p>The provider is what names the codecs. That keeps a {@link Codec} to describing a conversion,
 * and lets one codec be registered under several names — or the same codec instance be contributed
 * for several types — without the codec itself knowing.</p>
 *
 * <p>Providers are loaded once per class loader. A registration that collides with a built-in type
 * name is kept in its own layer: built-in types still resolve by OID, so the contributed codec
 * applies only to non-built-in types of that name.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public interface CodecProvider {

  /**
   * Returns the codecs this provider contributes.
   *
   * <p>Called once when the provider is loaded; the result is not consulted again, so it must not
   * depend on connection state.</p>
   *
   * @return the registrations, possibly empty, never null
   */
  List<CodecRegistration> codecs();
}
