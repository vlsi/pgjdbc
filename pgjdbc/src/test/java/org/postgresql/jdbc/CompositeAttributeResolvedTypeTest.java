/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.TypeInfo;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * The composite metadata load resolves each attribute's type once, so a codec decoding a row reads
 * the descriptor off the attribute rather than resolving it per field per row.
 *
 * <p>Without these assertions the cached path is invisible: {@code CompositeAttribute.getType()}
 * returning null is a legal cache miss that silently falls back to the per-row resolve, so a
 * regression would keep every value correct while quietly restoring the allocation.</p>
 */
class CompositeAttributeResolvedTypeTest {

  private static final String TYPE_NAME = "attr_resolved_type";

  private Connection con;

  @BeforeEach
  void setUp() throws SQLException {
    con = TestUtil.openDB();
    TestUtil.createCompositeType(con, TYPE_NAME,
        "amount numeric(10,2), label varchar(7), inner_range int4range");
  }

  @AfterEach
  void tearDown() throws SQLException {
    TestUtil.dropType(con, TYPE_NAME);
    TestUtil.closeDB(con);
  }

  private List<PgField> attributes() throws SQLException {
    TypeInfo typeInfo = con.unwrap(BaseConnection.class).getTypeInfo();
    return typeInfo.getFields(typeInfo.getPgTypeByPgName(TYPE_NAME).getOid());
  }

  @Test
  void attributesCarryResolvedTypeStampedWithAttypmod() throws SQLException {
    List<PgField> fields = attributes();
    assertEquals(3, fields.size(), "attribute count");

    for (PgField field : fields) {
      assertNotNull(field.getType(),
          () -> "attribute " + field.getName() + " must carry a resolved type");
    }

    // numeric(10,2) and varchar(7) pin a modifier; the descriptor must carry it, since that is what
    // makes the per-row resolve unnecessary.
    assertEquals(fields.get(0).getAppliedTypmod(), castType(fields.get(0)).getAppliedTypmod(),
        "numeric(10,2) applied typmod");
    assertEquals(fields.get(1).getAppliedTypmod(), castType(fields.get(1)).getAppliedTypmod(),
        "varchar(7) applied typmod");
  }

  @Test
  void rangeAttributeIsResolvedForItsKind() throws SQLException {
    // The invariant the codec layer relies on: a descriptor handed to a codec is resolved for its
    // own kind, so a range attribute already knows its subtype and RangeCodec never has to retry.
    TypeDescriptor rangeType = castType(attributes().get(2));
    assertEquals('r', rangeType.getTyptype(), "int4range typtype");
    assertEquals(org.postgresql.core.Oid.INT4, rangeType.getRangeSubtype(),
        "int4range subtype must be loaded, not 0");
  }

  @Test
  void repeatedLoadsShareTheSameDescriptorInstance() throws SQLException {
    // Resolution happens once per type, not once per lookup: the second load returns the cached
    // attribute list, so the descriptors are the very same objects.
    assertSame(attributes().get(0).getType(), attributes().get(0).getType(),
        "attribute descriptor must be cached, not rebuilt per lookup");
  }

  private static TypeDescriptor castType(PgField field) {
    TypeDescriptor type = field.getType();
    assertNotNull(type, "resolved attribute type");
    return type;
  }
}
