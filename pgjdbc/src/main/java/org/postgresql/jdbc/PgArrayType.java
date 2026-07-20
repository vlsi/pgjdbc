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

  /**
   * Constructs a new PgArrayType.
   *
   * @param typeName the type name
   * @param fullName the full name of the type
   * @param oid the OID of the type
   * @param elementType the element type
   */
  public PgArrayType(TypeName typeName, String fullName, int oid, PgType elementType) {
    super(typeName, fullName, oid, 'b', 'A', -1, elementType.getOid(), Oid.UNSPECIFIED, Oid.UNSPECIFIED);
    this.elementType = elementType;
  }

  /**
   * Gets the element type.
   *
   * @return the element type
   */
  public PgType getElementType() {
    return elementType;
  }

  /**
   * Creates a new PgArrayType from a base type.
   *
   * @param baseType the base type
   * @param arrayOid the OID of the array type
   * @return a new PgArrayType
   */
  public static PgArrayType fromBaseType(PgType baseType, int arrayOid) {
    TypeName arrayTypeName = TypeName.of(baseType.getName().getNamespace(), "_" + baseType.getName().getLocalName());
    String arrayFullName = baseType.getFormattedName() + "[]";
    return new PgArrayType(arrayTypeName, arrayFullName, arrayOid, baseType);
  }
}
