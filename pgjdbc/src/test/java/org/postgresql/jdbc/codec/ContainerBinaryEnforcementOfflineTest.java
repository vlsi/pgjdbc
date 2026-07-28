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

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Arrays;

/**
 * Pins the binary-encode enforcement gate for the delegating codecs. A composite, range, multirange
 * or domain built over a child type whose codec cannot binary-encode ({@code encodesBinary() ==
 * false} — the {@code time}/{@code timetz} shape, which is a {@link BinaryCodec} the server sends in
 * binary yet the driver only writes as text) must refuse a binary encode outright, never serialize
 * the child's text-shaped bytes into the binary wire format.
 *
 * <p>Two layers are pinned for each container:
 * <ul>
 *   <li><b>Negotiation</b> — {@code CodecFormatPolicy.chooseBindFormat} consults the recursive
 *       {@link BinaryCodec#canEncodeBinaryType}, so it picks {@link Format#TEXT} even with the backend
 *       accepting binary: the container reports its child's inability, so the value never enters the
 *       binary encode path.</li>
 *   <li><b>Enforcement</b> — a forced binary encode ({@code Codecs.encode(.., BINARY)}) still fails
 *       with a {@link SQLException}: {@code CodecFormatSupport.writeBinary} runs
 *       {@code requireBinaryEncoder} before any child byte is produced. The poison child's
 *       {@link PoisonChildCodec#encodeBinary} throws an {@link AssertionError} if it is ever reached,
 *       so a breach surfaces as a non-{@code SQLException} failure: proof the refusal happens at the
 *       gate, before the child encodes anything.</li>
 * </ul>
 *
 * <p>Each container still encodes the same value as <em>text</em>, since the child is a real
 * {@link TextCodec}: both layers are specific to binary, not a blanket refusal of the value.
 */
class ContainerBinaryEnforcementOfflineTest {

  private static final int CHILD_OID = 990_101;
  private static final int DOMAIN_OID = 990_102;
  private static final int RANGE_OID = 990_103;
  private static final int MULTIRANGE_OID = 990_104;
  private static final int COMPOSITE_OID = 990_105;

  /** A non-null value the container passes to the child; the child never encodes it in binary. */
  private static final Object CHILD_VALUE = "v";

  private static final PgType CHILD_TYPE =
      new PgType(TypeName.of("public", "poison"), "public.poison", CHILD_OID, 'b', 'U',
          -1, 0, 0, 0);
  private static final PgType DOMAIN_TYPE = domain("poison_domain", DOMAIN_OID, CHILD_OID);
  private static final PgType RANGE_TYPE =
      new PgType(TypeName.of("public", "poison_range"), "public.poison_range", RANGE_OID, 'r', 'R',
          -1, 0, 0, 0).withRangeSubtype(CHILD_OID);
  private static final PgType MULTIRANGE_TYPE =
      new PgType(TypeName.of("public", "poison_multirange"), "public.poison_multirange",
          MULTIRANGE_OID, 'm', 'R', -1, 0, 0, 0).withMultirangeRange(RANGE_OID);
  private static final PgType COMPOSITE_TYPE =
      composite("poison_composite", COMPOSITE_OID, field("f1", CHILD_OID, 1));

  /** A one-bound range and multirange, and a one-attribute struct, all reaching the child value. */
  private static final PGRange<Object> RANGE_VALUE = new PGRange<>(CHILD_VALUE, null, true, false);

  private static CodecContext newContext() {
    CodecRegistry registry = new CodecRegistry();
    // Only the child is a user OID binding; the container OIDs resolve to the built-in
    // range/multirange/domain/composite codecs structurally by typtype.
    registry.registerByOid(CHILD_OID, PoisonChildCodec.INSTANCE);
    return OfflineCodecs.builder()
        .codecLookup(registry)
        .type(CHILD_TYPE)
        .type(DOMAIN_TYPE)
        .type(RANGE_TYPE)
        .type(MULTIRANGE_TYPE)
        .type(COMPOSITE_TYPE)
        .build();
  }

  @Test
  void domainBinaryEncodeRefuses() {
    assertBinaryRefusedTextAccepted(DOMAIN_TYPE, CHILD_VALUE);
  }

  @Test
  void rangeBinaryEncodeRefuses() {
    assertBinaryRefusedTextAccepted(RANGE_TYPE, RANGE_VALUE);
  }

  @Test
  void multirangeBinaryEncodeRefuses() {
    assertBinaryRefusedTextAccepted(MULTIRANGE_TYPE, new PGmultirange<>(RANGE_VALUE));
  }

  @Test
  void compositeBinaryEncodeRefuses() {
    assertBinaryRefusedTextAccepted(COMPOSITE_TYPE, new PgStruct(COMPOSITE_TYPE,
        new Object[]{CHILD_VALUE}, null));
  }

  /**
   * For {@code value} as {@code type}: the format negotiation must pick {@link Format#TEXT} even with
   * the backend accepting binary (the recursive type check sees the binary-incapable child), a forced
   * binary encode must throw {@link SQLException} (the enforcement gate refuses; the poison child
   * never encodes, so no {@link AssertionError} escapes), and a text encode of the same value must
   * succeed and keep the text format.
   */
  private void assertBinaryRefusedTextAccepted(TypeDescriptor type, Object value) {
    CodecContext ctx = newContext();

    Format negotiated = assertDoesNotThrowSql(
        () -> CodecFormatPolicy.chooseBindFormat(ctx.resolveCodec(type.getOid()), value, type, ctx, true));
    assertEquals(Format.TEXT, negotiated,
        "negotiation must pick text for a container over a binary-incapable child, backend binary on");

    assertThrows(SQLException.class, () -> Codecs.encode(value, type, ctx, Format.BINARY),
        "binary encode must refuse a container over a child that cannot binary-encode");

    WireValueSlice text = assertDoesNotThrowSql(() -> Codecs.encode(value, type, ctx, Format.TEXT));
    assertEquals(Format.TEXT, text.getFormat(), "the same value still encodes as text");
  }

  private static <T> T assertDoesNotThrowSql(SqlSupplier<T> supplier) {
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

  // --- offline type builders (mirrors CodecNestingDepthOfflineTest) -----------------------------

  private static PgField field(String name, int oid, int position) {
    return new PgField(name, oid, position, -1);
  }

  private static PgType composite(String simpleName, int oid, PgField... fields) {
    return new PgType(TypeName.of("public", simpleName), "public." + simpleName, oid, 'c', 'C',
        -1, 0, 0, 0, ',', Arrays.asList(fields));
  }

  private static PgType domain(String simpleName, int oid, int baseOid) {
    return new PgType(TypeName.of("public", simpleName), "public." + simpleName, oid, 'd', 'N',
        -1, 0, 0, baseOid, null);
  }

  /**
   * A child codec that reads binary and reads/writes text, but declares it cannot produce a binary
   * payload — the {@code time}/{@code timetz} shape. {@link #encodeBinary} must never run: the
   * container's enforcement gate refuses before reaching it, so it throws to make a breach loud.
   */
  private static final class PoisonChildCodec implements BinaryCodec, TextCodec {
    static final PoisonChildCodec INSTANCE = new PoisonChildCodec();

    @Override
    public Class<?> getDefaultJavaType() {
      return String.class;
    }

    @Override
    public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
        CodecContext ctx) {
      return CHILD_VALUE;
    }

    @Override
    public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) {
      throw new AssertionError("encodeBinary must not be reached: the enforcement gate must refuse "
          + "a binary encode before the child produces any bytes");
    }

    @Override
    public boolean decodesBinary() {
      return true;
    }

    @Override
    public boolean encodesBinary() {
      return false;
    }

    @Override
    public @Nullable Object decodeText(CharSequence data, TypeDescriptor type, CodecContext ctx) {
      return CHILD_VALUE;
    }

    @Override
    public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) {
      return "42";
    }

    @Override
    public boolean decodesText() {
      return true;
    }
  }
}
