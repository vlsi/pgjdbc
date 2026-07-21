/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.postgresql.api.codec.BackpatchingByteArrayOutputStream;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.StreamingTextCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeName;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.OfflineCodecs;
import org.postgresql.jdbc.PgField;
import org.postgresql.jdbc.PgStruct;
import org.postgresql.jdbc.PgType;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Struct;
import java.util.Arrays;
import java.util.Map;

/**
 * Pins the precedence between the two composite value shapes across every encode path. {@link Struct}
 * is declarative data whose attributes the driver can introspect to negotiate a format; {@link
 * SQLData} is an active callback whose values exist only once {@code writeSQL} runs. A value that
 * implements both is encoded through its {@code Struct} attributes — the more powerful, introspectable
 * representation — so {@code writeSQL} is not run.
 *
 * <p>All four composite encode paths check {@code instanceof Struct} before {@code instanceof
 * SQLData}: buffered binary, streaming binary, buffered text and streaming text. Each case runs the
 * dual value and a plain {@link PgStruct} with the same attributes through the same path and requires
 * identical output and an unrun {@code writeSQL}, so a reordering that slips in any one path is
 * caught.
 */
class StructPreferredOverSqlDataOfflineTest {

  private static final int COMPOSITE_OID = 993_101;
  private static final PgType COMPOSITE_TYPE =
      composite("dual_composite", COMPOSITE_OID, field("f1", Oid.INT4, 1));

  @Test
  void bufferedBinaryPrefersStruct() throws Exception {
    assertBinaryPathPrefersStruct(
        (value, ctx) -> Codecs.encode(value, COMPOSITE_TYPE, ctx, Format.BINARY).toByteArray());
  }

  @Test
  void streamingBinaryPrefersStruct() throws Exception {
    assertBinaryPathPrefersStruct((value, ctx) -> {
      BackpatchingByteArrayOutputStream out = new BackpatchingByteArrayOutputStream();
      streamingBinary(ctx).encodeBinary(value, COMPOSITE_TYPE, ctx, out);
      return out.toByteArray();
    });
  }

  @Test
  void bufferedTextPrefersStruct() throws Exception {
    assertTextPathPrefersStruct((value, ctx) -> textCodec(ctx).encodeText(value, COMPOSITE_TYPE, ctx));
  }

  @Test
  void streamingTextPrefersStruct() throws Exception {
    assertTextPathPrefersStruct((value, ctx) -> {
      StringBuilder sb = new StringBuilder();
      streamingText(ctx).encodeText(value, COMPOSITE_TYPE, ctx, sb);
      return sb.toString();
    });
  }

  /**
   * Encodes the dual value and a plain {@link PgStruct} with the same attributes through {@code path};
   * requires that the dual value did not run {@code writeSQL} (it took the Struct branch) and that both
   * encodings are identical.
   */
  private void assertBinaryPathPrefersStruct(EncodeBinary path) throws SQLException, IOException {
    CodecContext ctx = OfflineCodecs.builder().type(COMPOSITE_TYPE).build();
    DualValue dual = new DualValue(42);

    byte[] fromDual = path.encode(dual, ctx);

    assertFalse(dual.writeSqlCalled,
        "a value that is both Struct and SQLData must encode via its Struct attributes, not writeSQL");
    byte[] fromStruct = path.encode(new PgStruct(COMPOSITE_TYPE, new Object[]{42}, null), ctx);
    assertArrayEquals(fromStruct, fromDual,
        "the Struct-attribute encoding must match a plain PgStruct with the same attributes");
  }

  private void assertTextPathPrefersStruct(EncodeText path) throws SQLException, IOException {
    CodecContext ctx = OfflineCodecs.builder().type(COMPOSITE_TYPE).build();
    DualValue dual = new DualValue(42);

    String fromDual = path.encode(dual, ctx);

    assertFalse(dual.writeSqlCalled,
        "a value that is both Struct and SQLData must encode via its Struct attributes, not writeSQL");
    String fromStruct = path.encode(new PgStruct(COMPOSITE_TYPE, new Object[]{42}, null), ctx);
    assertEquals(fromStruct, fromDual,
        "the Struct-attribute encoding must match a plain PgStruct with the same attributes");
  }

  private static StreamingBinaryCodec streamingBinary(CodecContext ctx) throws SQLException {
    return (StreamingBinaryCodec) requireNonNull(ctx.resolveBinaryCodec(COMPOSITE_OID));
  }

  private static TextCodec textCodec(CodecContext ctx) throws SQLException {
    return requireNonNull(ctx.resolveTextCodec(COMPOSITE_OID));
  }

  private static StreamingTextCodec streamingText(CodecContext ctx) throws SQLException {
    return (StreamingTextCodec) textCodec(ctx);
  }

  @FunctionalInterface
  private interface EncodeBinary {
    byte[] encode(Object value, CodecContext ctx) throws SQLException, IOException;
  }

  @FunctionalInterface
  private interface EncodeText {
    String encode(Object value, CodecContext ctx) throws SQLException, IOException;
  }

  private static PgField field(String name, int oid, int position) {
    return new PgField(name, oid, position, -1);
  }

  private static PgType composite(String simpleName, int oid, PgField... fields) {
    return new PgType(TypeName.of("public", simpleName), "public." + simpleName, oid, 'c', 'C',
        -1, 0, 0, 0, ',', Arrays.asList(fields));
  }

  /** A composite value that is both a declarative {@link Struct} and an active {@link SQLData}. */
  private static final class DualValue implements Struct, SQLData {
    private final int attribute;
    boolean writeSqlCalled;

    DualValue(int attribute) {
      this.attribute = attribute;
    }

    @Override
    public String getSQLTypeName() {
      return "public.dual_composite";
    }

    @Override
    public Object[] getAttributes() {
      return new Object[]{attribute};
    }

    @Override
    public Object[] getAttributes(Map<String, Class<?>> map) {
      return getAttributes();
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) {
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      writeSqlCalled = true;
      stream.writeInt(attribute);
    }
  }
}
