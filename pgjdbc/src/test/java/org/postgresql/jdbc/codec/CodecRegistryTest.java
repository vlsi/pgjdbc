/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.api.codec.TypeName;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.CodecRegistry;
import org.postgresql.jdbc.PgType;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CodecRegistry} resolution: OID-cache coherence, OID-primary identity for
 * built-in types, and the user &gt; SPI &gt; built-in layering.
 */
class CodecRegistryTest {

  /** A minimal codec usable as a registration target; only the marker methods are exercised. */
  private static final class StubCodec implements Codec {
    private final String typeName;

    StubCodec(String typeName) {
      this.typeName = typeName;
    }

    /** The name this stub is registered under; the codec API itself no longer carries one. */
    String typeName() {
      return typeName;
    }

    @Override
    public Class<?> getDefaultJavaType() {
      return Object.class;
    }
  }

  /** A binary codec that opts out of binary reads, exercising the read-side capability gate. */
  private static final class BinaryReadOptOutCodec implements BinaryCodec {

    @Override
    public Class<?> getDefaultJavaType() {
      return Object.class;
    }

    @Override
    public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx) {
      return null;
    }

    @Override
    public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) {
      return new byte[0];
    }

    @Override
    public boolean decodesBinary() {
      return false;
    }
  }

  /** A user type whose typtype/typcategory leave it unresolved until a name-based codec exists. */
  private static PgType userType(String name, int oid) {
    return new PgType(TypeName.of("public", name), name, oid, 'b', 'U', -1, 0, 0, 0);
  }

  /** A built-in scalar type living in {@code pg_catalog}. */
  private static PgType builtinType(String name, int oid) {
    return new PgType(TypeName.of("pg_catalog", name), name, oid, 'b', 'N', -1, 0, 0, 0);
  }

  /** A user-defined composite type in an arbitrary schema. */
  private static PgType compositeType(String namespace, String name, int oid) {
    return new PgType(TypeName.of(namespace, name), namespace + "." + name, oid, 'c', 'C', -1, 0, 0, 0);
  }

  @Test
  void registerByName_invalidatesOidCache() {
    CodecRegistry registry = new CodecRegistry();
    PgType type = userType("codecregistry_byname", 999_001);

    StubCodec first = new StubCodec("codecregistry_byname");
    registry.registerByName(first.typeName(), first);
    // The first resolve caches `first` for this OID.
    assertSame(first, registry.getByOid(type.getOid(), type));

    StubCodec second = new StubCodec("codecregistry_byname");
    registry.registerByName(second.typeName(), second);
    // The re-registration must invalidate the OID cache; otherwise getByOid would keep
    // returning the stale `first`.
    assertSame(second, registry.getByOid(type.getOid(), type));
  }

  @Test
  void registerAlias_invalidatesOidCache() {
    CodecRegistry registry = new CodecRegistry();
    PgType type = userType("codecregistry_alias", 999_002);

    StubCodec first = new StubCodec("codecregistry_alias_a");
    registry.registerAlias("codecregistry_alias", first);
    assertSame(first, registry.getByOid(type.getOid(), type));

    StubCodec second = new StubCodec("codecregistry_alias_b");
    registry.registerAlias("codecregistry_alias", second);
    assertSame(second, registry.getByOid(type.getOid(), type));
  }

  @Test
  void getByOid_resolvesBuiltinByCanonicalOid() {
    CodecRegistry registry = new CodecRegistry();
    assertSame(PointCodec.INSTANCE, registry.getByOid(Oid.POINT, builtinType("point", Oid.POINT)));
    assertSame(Int4Codec.INSTANCE, registry.getByOid(Oid.INT4, builtinType("int4", Oid.INT4)));
  }

  @Test
  void getByOid_userTypeNamedLikeBuiltin_doesNotResolveToBuiltinCodec() {
    CodecRegistry registry = new CodecRegistry();
    // A user composite named "point" in another schema must resolve as a composite,
    // not be captured by the built-in geometric "point" codec via its bare name.
    PgType userPoint = compositeType("myschema", "point", 990_100);
    assertSame(CompositeCodec.INSTANCE, registry.getByOid(userPoint.getOid(), userPoint));
  }

  @Test
  void getByOid_userNameRegistrationOverridesBuiltin() {
    CodecRegistry registry = new CodecRegistry();
    PgType int4 = builtinType("int4", Oid.INT4);
    assertSame(Int4Codec.INSTANCE, registry.getByOid(Oid.INT4, int4));

    StubCodec custom = new StubCodec("int4");
    registry.registerByName(custom.typeName(), custom);
    // The user layer outranks the built-in OID identity.
    assertSame(custom, registry.getByOid(Oid.INT4, int4));
    assertNotSame(Int4Codec.INSTANCE, registry.getByOid(Oid.INT4, int4));
  }

  @Test
  void getByName_resolvesBuiltinByBareName() {
    CodecRegistry registry = new CodecRegistry();
    // pg_catalog types are reachable by their bare name.
    assertSame(Int4Codec.INSTANCE, registry.getByLocalName("int4"));
    assertSame(PointCodec.INSTANCE, registry.getByLocalName("point"));
    // hstore is a bare-name extension codec.
    assertSame(HstoreCodec.INSTANCE, registry.getByLocalName("hstore"));
    // No codec is registered for the array type name.
    assertNull(registry.getByLocalName("_int4"));
  }

  @Test
  void canDecodeBinary_followsCodecCapability() {
    CodecRegistry registry = new CodecRegistry();
    // Every geometric codec, point and circle alike, decodes binary.
    assertTrue(registry.canDecodeBinary(Oid.POINT, builtinType("point", Oid.POINT)));
    assertTrue(registry.canDecodeBinary(Oid.CIRCLE, builtinType("circle", Oid.CIRCLE)));

    // A binary codec that opts out of binary reads is gated by the capability, not instanceof.
    Codec optOut = new BinaryReadOptOutCodec();
    registry.registerByName("binary_read_optout", optOut);
    PgType type = userType("binary_read_optout", 990_200);
    assertSame(optOut, registry.getByOid(type.getOid(), type));
    assertFalse(registry.canDecodeBinary(type.getOid(), type));
  }

  @Test
  void resetCustomCodecs_clearsEveryUserLayerRegistration() {
    CodecRegistry registry = new CodecRegistry();

    StubCodec byName = new StubCodec("reset_byname");
    registry.registerByName(byName.typeName(), byName);

    StubCodec aliased = new StubCodec("reset_alias_target");
    registry.registerAlias("reset_alias", aliased);

    StubCodec custom = new StubCodec("reset_custom");
    registry.registerCustomCodec(custom.typeName(), custom);

    StubCodec byOid = new StubCodec("reset_byoid");
    PgType byOidType = userType("reset_byoid", 999_010);
    registry.registerByOid(byOidType.getOid(), byOid);

    // All four user-layer registrations resolve before the reset.
    assertSame(byName, registry.getByLocalName("reset_byname"));
    assertSame(aliased, registry.getByLocalName("reset_alias"));
    assertSame(custom, registry.getByLocalName("reset_custom"));
    assertSame(byOid, registry.getByOid(byOidType.getOid(), byOidType));

    registry.resetCustomCodecs();

    // None survive. The name registrations from registerByName/registerAlias, which the old
    // customCodecNames tracking missed and thus leaked across pooled logical connections, are
    // cleared along with the registerCustomCodec and registerByOid bindings.
    assertNull(registry.getByLocalName("reset_byname"));
    assertNull(registry.getByLocalName("reset_alias"));
    assertNull(registry.getByLocalName("reset_custom"));
    assertNotSame(byOid, registry.getByOid(byOidType.getOid(), byOidType));
  }

  @Test
  void unregisterCustomCodec_removesRegisterByNameRegistration() {
    CodecRegistry registry = new CodecRegistry();

    StubCodec byName = new StubCodec("unregister_byname");
    registry.registerByName(byName.typeName(), byName);
    assertSame(byName, registry.getByLocalName("unregister_byname"));

    // unregisterCustomCodec removes any user-layer codec of that name, not only those added
    // through registerCustomCodec.
    registry.unregisterCustomCodec("unregister_byname");
    assertNull(registry.getByLocalName("unregister_byname"));
  }

  @Test
  void canDecodeText_followsCodecCapability() {
    CodecRegistry registry = new CodecRegistry();
    // Every geometric codec, point and circle alike, can also read text.
    assertTrue(registry.canDecodeText(Oid.POINT, builtinType("point", Oid.POINT)));
    assertTrue(registry.canDecodeText(Oid.CIRCLE, builtinType("circle", Oid.CIRCLE)));

    // A binary-only codec cannot decode text.
    Codec optOut = new BinaryReadOptOutCodec();
    registry.registerByName("binary_read_optout", optOut);
    PgType type = userType("binary_read_optout", 990_201);
    assertFalse(registry.canDecodeText(type.getOid(), type));
  }
}
