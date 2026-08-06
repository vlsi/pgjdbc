/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.api.codec.TypeName;
import org.postgresql.core.Oid;

/**
 * Represents a PostgreSQL array type.
 */
public class PgArrayType extends PgType {
  private final PgType elementType;

  public PgArrayType(TypeName typeName, String fullName, int oid, PgType elementType) {
    super(typeName, fullName, oid, 'b', 'A', -1, elementType.getOid(), Oid.UNSPECIFIED, Oid.UNSPECIFIED);
    this.elementType = elementType;
  }

  public PgType getElementType() {
    return elementType;
  }

  /**
   * Creates the array type whose elements are {@code baseType}, named the way PostgreSQL names one:
   * the element type's namespace, its local name prefixed with {@code _}, and a formatted name
   * suffixed with {@code []}.
   */
  public static PgArrayType fromBaseType(PgType baseType, int arrayOid) {
    TypeName arrayTypeName = TypeName.of(baseType.getName().getNamespace(), "_" + baseType.getName().getLocalName());
    String arrayFullName = baseType.getFormattedName() + "[]";
    return new PgArrayType(arrayTypeName, arrayFullName, arrayOid, baseType);
  }
}
