/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The catalog identity of a PostgreSQL type: {@code pg_type.typname} plus the schema it lives in
 * ({@code pg_namespace.nspname}).
 *
 * <p>This is the catalog spelling, not the SQL-standard one: {@code bool}, not {@code boolean}, and
 * an array type keeps its leading underscore ({@code _int4}). For the name to show a user, take
 * {@link TypeDescriptor#getFormattedName()}.</p>
 *
 * <p>A {@code TypeName} is an immutable value. Two names are equal when both the namespace and the
 * local name are equal, so instances work as map keys. {@code toString()} is for diagnostics; its
 * exact form is not part of the contract, so build a displayable name from
 * {@link TypeDescriptor#getFormattedName()} rather than parsing it.</p>
 *
 * <p>This is a final class rather than an interface on purpose. A name carries no state or behavior
 * beyond the two strings, and equality across independent implementations of an interface cannot be
 * both symmetric and value-based — an offline name would never equal a driver name for the same
 * type.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public final class TypeName {

  private final @Nullable String namespace;
  private final String localName;

  private TypeName(@Nullable String namespace, String localName) {
    this.namespace = namespace;
    this.localName = localName;
  }

  /**
   * Returns a schema-qualified name.
   *
   * @param namespace the namespace ({@code pg_namespace.nspname}), or {@code null} to leave the name
   *     unqualified
   * @param localName the local name ({@code pg_type.typname})
   * @return the name
   * @throws NullPointerException if {@code localName} is null
   */
  public static TypeName of(@Nullable String namespace, String localName) {
    if (localName == null) {
      throw new NullPointerException("localName");
    }
    return new TypeName(namespace, localName);
  }

  /**
   * Returns an unqualified name, for a type identified by {@code pg_type.typname} alone.
   *
   * @param localName the local name ({@code pg_type.typname})
   * @return the name, reporting {@code null} from {@link #getNamespace()}
   * @throws NullPointerException if {@code localName} is null
   */
  public static TypeName of(String localName) {
    return of(null, localName);
  }

  /**
   * Returns the namespace (schema) part, or {@code null} for an unqualified name.
   *
   * @return the namespace, or {@code null}
   */
  public @Nullable String getNamespace() {
    return namespace;
  }

  /**
   * Returns the local (unqualified) name — {@code pg_type.typname}.
   *
   * @return the name
   */
  public String getLocalName() {
    return localName;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof TypeName)) {
      return false;
    }
    TypeName that = (TypeName) obj;
    return localName.equals(that.localName)
        && (namespace == null ? that.namespace == null : namespace.equals(that.namespace));
  }

  @Override
  public int hashCode() {
    return (namespace == null ? 0 : namespace.hashCode()) * 31 + localName.hashCode();
  }

  /**
   * Returns the schema-qualified catalog spelling, for diagnostics only. Not a displayable type name
   * — see {@link TypeDescriptor#getFormattedName()} for that.
   *
   * @return the name, qualified when a namespace is set
   */
  @Override
  public String toString() {
    return namespace == null ? localName : namespace + "." + localName;
  }
}
