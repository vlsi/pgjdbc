/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.api.codec.CompositeAttribute;
import org.postgresql.api.codec.TypeDescriptor;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Represents a field in a PostgreSQL composite type.
 * Fields are loaded eagerly when a composite type is first accessed.
 */
public final class PgField implements CompositeAttribute {
  private final String name;
  private final int typeOid;
  private final int position;
  private final int typmod;
  private final @Nullable TypeDescriptor type;

  /**
   * Constructs a new PgField whose type the caller resolves through the codec context.
   *
   * @param name the field name
   * @param typeOid the OID of the field's type
   * @param position the 1-based position of the field in the composite type
   * @param typmod the type modifier (e.g., for varchar(n))
   */
  public PgField(String name, int typeOid, int position, int typmod) {
    this(name, typeOid, position, typmod, null);
  }

  /**
   * Constructs a new PgField carrying its already-resolved type, so a codec decoding a row reads
   * the descriptor off the attribute instead of resolving it per field per row.
   *
   * @param name the field name
   * @param typeOid the OID of the field's type
   * @param position the 1-based position of the field in the composite type
   * @param typmod the type modifier (e.g., for varchar(n))
   * @param type the resolved field type stamped with {@code typmod}, or null if unresolved
   */
  public PgField(String name, int typeOid, int position, int typmod,
      @Nullable TypeDescriptor type) {
    this.name = name;
    this.typeOid = typeOid;
    this.position = position;
    this.typmod = typmod;
    this.type = type;
  }

  /**
   * Gets the field name.
   *
   * @return the field name
   */
  @Override
  public String getName() {
    return name;
  }

  /**
   * Gets the OID of the field's type.
   * Use {@link TypeInfoCache#getPgTypeByOid(int)} to resolve the full type information.
   *
   * @return the type OID
   */
  @Override
  public int getTypeOid() {
    return typeOid;
  }

  /**
   * Gets the 1-based position of the field in the composite type.
   *
   * @return the field position
   */
  public int getPosition() {
    return position;
  }

  /**
   * Gets the type modifier for this field.
   * For example, for varchar(100) this would encode the length 100.
   *
   * @return the type modifier, or -1 if not applicable
   */
  @Override
  public int getAppliedTypmod() {
    return typmod;
  }

  @Override
  public @Nullable TypeDescriptor getType() {
    return type;
  }

  @Override
  public String toString() {
    return name + " (oid=" + typeOid + ", pos=" + position + ", typmod=" + typmod + ")";
  }
}
