/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.api.codec.TypeName;
import org.postgresql.api.codec.WireValueSlice;
import org.postgresql.jdbc.CodecRegistry;
import org.postgresql.jdbc.OfflineCodecs;
import org.postgresql.jdbc.PgField;
import org.postgresql.jdbc.PgStruct;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.PGRange;
import org.postgresql.util.PGmultirange;
import org.postgresql.util.PGobject;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * Pins the <em>value-level</em> recursion of the binary bind negotiation. A container's type tree can
 * be fully binary-capable while a particular nested value is not: a composite attribute, range bound
 * or multirange range that arrives as a plain {@link PGobject} carries only text. The child codec
 * binds a typed value in binary but a plain {@code PGobject} as text — the shape of every scalar and
 * composite codec — so the reviewer's {@code createStruct("outer", {new PGobject(...)})} must
 * negotiate text even though the outer and inner types are both binary types.
 *
 * <p>Without the recursion the structural check ({@code canEncodeBinaryType}) passes and only the
 * top-level value is inspected — a {@link java.sql.Struct} is unconditionally "binary" — so binary is
 * chosen and the encode fails only when the child codec rejects the nested {@code PGobject}. This
 * test drives all three delegating value paths (composite attribute, range bound, multirange range)
 * in both directions: the {@code PGobject} value negotiates text and refuses a forced binary encode,
 * while a typed value the child accepts negotiates and encodes binary.
 */
class NestedContainerValueBindOfflineTest {

  private static final int CHILD_OID = 991_101;
  private static final int RANGE_OID = 991_102;
  private static final int MULTIRANGE_OID = 991_103;
  private static final int COMPOSITE_OID = 991_104;

  /** A plain PGobject: binary-incapable at the value level, like any composite/scalar over a PGobject. */
  private static final PGobject UNTYPED = untyped();
  /** A typed value the child binds in binary. */
  private static final Object TYPED = "typed";

  private static final PgType CHILD_TYPE =
      new PgType(TypeName.of("public", "picky"), "public.picky", CHILD_OID, 'b', 'U', -1, 0, 0, 0);
  private static final PgType RANGE_TYPE =
      new PgType(TypeName.of("public", "picky_range"), "public.picky_range", RANGE_OID, 'r', 'R',
          -1, 0, 0, 0).withRangeSubtype(CHILD_OID);
  private static final PgType MULTIRANGE_TYPE =
      new PgType(TypeName.of("public", "picky_multirange"), "public.picky_multirange",
          MULTIRANGE_OID, 'm', 'R', -1, 0, 0, 0).withMultirangeRange(RANGE_OID);
  private static final PgType COMPOSITE_TYPE =
      composite("picky_composite", COMPOSITE_OID, field("f1", CHILD_OID, 1));

  private static CodecContext newContext() {
    CodecRegistry registry = new CodecRegistry();
    registry.registerByOid(CHILD_OID, PickyChildCodec.INSTANCE);
    return OfflineCodecs.builder()
        .registry(registry)
        .type(CHILD_TYPE)
        .type(RANGE_TYPE)
        .type(MULTIRANGE_TYPE)
        .type(COMPOSITE_TYPE)
        .build();
  }

  @Test
  void compositeAttribute() {
    assertNestedValueGovernsFormat(COMPOSITE_TYPE,
        new PgStruct(COMPOSITE_TYPE, new Object[]{UNTYPED}, null),
        new PgStruct(COMPOSITE_TYPE, new Object[]{TYPED}, null));
  }

  @Test
  void rangeBound() {
    assertNestedValueGovernsFormat(RANGE_TYPE,
        new PGRange<>(UNTYPED, null, true, false),
        new PGRange<>(TYPED, null, true, false));
  }

  @Test
  void multirangeRange() {
    assertNestedValueGovernsFormat(MULTIRANGE_TYPE,
        new PGmultirange<>(new PGRange<Object>(UNTYPED, null, true, false)),
        new PGmultirange<>(new PGRange<Object>(TYPED, null, true, false)));
  }

  /**
   * The value carrying the untyped nested value must negotiate text and refuse a forced binary
   * encode, while the value carrying the typed nested value negotiates and encodes binary — proof the
   * decision follows the nested value, not just the top-level container.
   */
  private void assertNestedValueGovernsFormat(TypeDescriptor type, Object untypedValue,
      Object typedValue) {
    CodecContext ctx = newContext();

    Format negotiatedUntyped = choose(ctx, type, untypedValue);
    assertEquals(Format.TEXT, negotiatedUntyped,
        "a nested untyped value must make the container negotiate text");
    assertThrows(SQLException.class, () -> Codecs.encode(untypedValue, type, ctx, Format.BINARY),
        "a forced binary encode must refuse the untyped nested value");
    WireValueSlice text = sql(() -> Codecs.encode(untypedValue, type, ctx, Format.TEXT));
    assertEquals(Format.TEXT, text.getFormat(), "the untyped value still encodes as text");

    Format negotiatedTyped = choose(ctx, type, typedValue);
    assertEquals(Format.BINARY, negotiatedTyped,
        "a nested typed value must let the container negotiate binary");
    WireValueSlice binary = sql(() -> Codecs.encode(typedValue, type, ctx, Format.BINARY));
    assertEquals(Format.BINARY, binary.getFormat(), "the typed value encodes as binary");
  }

  private static Format choose(CodecContext ctx, TypeDescriptor type, Object value) {
    return sql(() -> CodecFormatPolicy.chooseBindFormat(ctx.resolveCodec(type.getOid()), value, type,
        ctx, true));
  }

  private static <T> T sql(SqlSupplier<T> supplier) {
    try {
      return supplier.get();
    } catch (SQLException e) {
      throw new AssertionError("must not throw: " + e.getMessage(), e);
    }
  }

  @FunctionalInterface
  private interface SqlSupplier<T> {
    T get() throws SQLException;
  }

  private static PGobject untyped() {
    PGobject o = new PGobject();
    o.setType("picky");
    try {
      o.setValue("x");
    } catch (SQLException e) {
      throw new AssertionError(e);
    }
    return o;
  }

  private static PgField field(String name, int oid, int position) {
    return new PgField(name, oid, position, -1);
  }

  private static PgType composite(String simpleName, int oid, PgField... fields) {
    return new PgType(TypeName.of("public", simpleName), "public." + simpleName, oid, 'c', 'C',
        -1, 0, 0, 0, ',', Arrays.asList(fields));
  }

  /**
   * Binds a typed value in binary, a plain {@link PGobject} as text only — the value-level shape of a
   * scalar or composite codec. {@link #encodeBinary} throws for a {@code PGobject} so a breach of the
   * negotiation surfaces as a non-{@code SQLException} failure.
   */
  private static final class PickyChildCodec implements BinaryCodec, TextCodec {
    static final PickyChildCodec INSTANCE = new PickyChildCodec();

    @Override
    public Class<?> getDefaultJavaType() {
      return String.class;
    }

    @Override
    public boolean canEncodeBinary(Object value, TypeDescriptor type, CodecContext ctx) {
      return !(value instanceof PGobject);
    }

    @Override
    public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) {
      if (value instanceof PGobject) {
        throw new AssertionError("encodeBinary must not be reached for a PGobject: the negotiation "
            + "must pick text and a forced binary encode must refuse before this codec runs");
      }
      return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
        CodecContext ctx) {
      return new String(data, offset, length, StandardCharsets.UTF_8);
    }

    @Override
    public @Nullable Object decodeText(CharSequence data, TypeDescriptor type, CodecContext ctx) {
      return data.toString();
    }

    @Override
    public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) {
      return value instanceof PGobject ? String.valueOf(((PGobject) value).getValue()) : value.toString();
    }
  }
}
