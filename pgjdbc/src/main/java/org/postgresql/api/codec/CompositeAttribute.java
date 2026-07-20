/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One attribute of a composite {@link TypeDescriptor}: its name and the OID of its type.
 *
 * <p>This is the read-only view a {@link TypeDescriptor} exposes to codecs. Prefer
 * {@link #getType()}, which the driver pre-resolves; fall back to resolving {@link #getTypeOid()}
 * through the codec context when it reports {@code null}. The driver's internal field type
 * implements this interface and may carry further detail (position) that the codec layer does not
 * need.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public interface CompositeAttribute {

  /**
   * Returns the field name.
   *
   * @return the field name
   */
  String getName();

  /**
   * Returns the OID of the field's type.
   *
   * @return the type OID
   */
  int getTypeOid();

  /**
   * Returns the attribute's type modifier ({@code pg_attribute.atttypmod}), for example the precision
   * and scale of a {@code numeric(10,2)} field. A codec stamps this onto the field's descriptor
   * ({@code ctx.resolveType(getTypeOid(), getAppliedTypmod())}) so a modifier-sensitive field decodes
   * correctly. The default is {@code -1}, meaning no modifier applies.
   *
   * @return the attribute type modifier, or {@code -1} when none applies
   */
  default int getAppliedTypmod() {
    return -1;
  }

  /**
   * Returns this attribute's type, already resolved and stamped with {@link #getAppliedTypmod()}, or
   * {@code null} when the driver has no cached descriptor for it.
   *
   * <p>A connection-bound composite resolves its attribute types once, when the type's metadata is
   * loaded, so a codec decoding a row reads the descriptor off the attribute instead of calling
   * {@code ctx.resolveType(getTypeOid(), getAppliedTypmod())} per field per row.</p>
   *
   * <p>{@code null} is a cache miss, not a statement about the type: it means the caller resolves
   * the type itself through {@link CodecContext#resolveType(int, int)}. Offline attributes, built
   * without a connection, report {@code null}.</p>
   *
   * @return the resolved attribute type, or {@code null} if the caller must resolve it
   */
  default @Nullable TypeDescriptor getType() {
    return null;
  }
}
