/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.api.codec.BackpatchingByteArrayOutputStream;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.CodecContextBuilder;
import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.TypeName;
import org.postgresql.api.codec.WireValueSlice;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.CodecDepth;
import org.postgresql.jdbc.OfflineCodecs;
import org.postgresql.jdbc.PgField;
import org.postgresql.jdbc.PgStruct;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Struct;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * Behavioural guard that every delegating codec bounds its recursion through {@link CodecDepth}, so a
 * value nested past the limit fails with a clear {@code DATA_ERROR} rather than overflowing the stack,
 * and a value within the limit still round-trips. Unlike {@link CodecDepthTest}, which drives
 * {@code CodecDepth} in isolation, and {@code NestingDepthTest}, which needs a live server, these run
 * fully offline through the public {@link Codecs} surface and exercise the real encode/decode
 * delegation paths in both wire formats.
 *
 * <p>Rather than build a value 64+ levels deep — impossible for the <em>text</em> form, whose
 * {@code record_out} quote-doubling makes it grow as {@code O(2^depth)} and exhaust the heap around
 * depth 30 — each test pre-seeds the depth counter so only a small budget of further levels remains
 * (see {@link #withDepthBudget}). The guard then trips at a shallow structure depth where the text
 * form is still tiny, letting both formats be checked behaviourally. This depends only on
 * {@code CodecDepth}'s public {@link CodecDepth#enter()} / {@link CodecDepth#current()} contract, not
 * on the numeric value of {@code MAX_DEPTH}.
 *
 * <p>The array cases build an {@code array -> composite -> array} cycle because a PostgreSQL array type
 * never nests into itself directly (multidimensionality is one type, capped at
 * {@code MultiDimArraySupport.MAX_DIMENSIONS}); the only unbounded array recursion runs through a
 * composite/domain element, so that is what the test constructs.
 */
class CodecNestingDepthOfflineTest {

  private static final int BUDGET = 8;

  @BeforeEach
  @AfterEach
  void resetDepth() {
    // The counter is a thread-local shared with the codecs; reset around every test so a pre-seeded
    // or leaked value cannot cross test boundaries.
    CodecDepth.clear();
  }

  // --- composite: exact boundary in both formats -----------------------------------------------

  @Test
  void compositeBinaryEncodeAtBudgetPassesOverBudgetThrows() throws Throwable {
    PgType recordType = anonymousRecord(field("f1", Oid.RECORD, 1));
    PgType leafType = anonymousRecord(field("f1", Oid.INT4, 1));
    CodecContext ctx = OfflineCodecs.builder().build();

    assertPassesAtThrowsOver(recordType, leafType, ctx, Format.BINARY);
  }

  @Test
  void compositeTextEncodeAtBudgetPassesOverBudgetThrows() throws Throwable {
    // Regression for the text composite encode path, which previously recursed through nested records
    // without a depth guard (only the binary encode path had one).
    PgType recordType = anonymousRecord(field("f1", Oid.RECORD, 1));
    PgType leafType = anonymousRecord(field("f1", Oid.INT4, 1));
    CodecContext ctx = OfflineCodecs.builder().build();

    assertPassesAtThrowsOver(recordType, leafType, ctx, Format.TEXT);
  }

  @Test
  void compositeBinaryDecodeAtBudgetPassesOverBudgetThrows() throws Throwable {
    PgType recordType = anonymousRecord(field("f1", Oid.RECORD, 1));
    CodecContext ctx = OfflineCodecs.builder().build();

    // A record nested exactly BUDGET deep decodes; one deeper trips the guard.
    withDepthBudget(BUDGET, false,
        () -> Codecs.decode(WireValueSlice.binary(nestedRecordBinary(BUDGET)), recordType, ctx, Struct.class));
    withDepthBudget(BUDGET, true,
        () -> Codecs.decode(WireValueSlice.binary(nestedRecordBinary(BUDGET + 1)), recordType, ctx, Struct.class));
  }

  /** Encodes a record nested exactly {@code BUDGET} deep (passes) and one deeper (throws). */
  private void assertPassesAtThrowsOver(PgType recordType, PgType leafType, CodecContext ctx,
      Format format) throws Throwable {
    withDepthBudget(BUDGET, false,
        () -> Codecs.encode(nestedRecordStruct(BUDGET, recordType, leafType), recordType, ctx, format));
    withDepthBudget(BUDGET, true,
        () -> Codecs.encode(nestedRecordStruct(BUDGET + 1, recordType, leafType), recordType, ctx, format));
  }

  // --- array <-> composite cycle: guard trips in both formats ----------------------------------

  @Test
  void arrayCompositeCycleBinaryEncodeThrows() throws Throwable {
    Cycle cycle = arrayCompositeCycle();
    PgStruct value = arrayCompositeCycleValue(BUDGET, cycle);
    withDepthBudget(BUDGET, true,
        () -> Codecs.encode(value, cycle.recordType, cycle.ctx, Format.BINARY));
  }

  @Test
  void arrayCompositeCycleTextEncodeThrows() throws Throwable {
    // With a small budget the guard trips a few levels in, so the text form's escaping stays tiny and
    // this exercises the array leaf's text encode guard without exhausting the heap.
    Cycle cycle = arrayCompositeCycle();
    PgStruct value = arrayCompositeCycleValue(BUDGET, cycle);
    withDepthBudget(BUDGET, true,
        () -> Codecs.encode(value, cycle.recordType, cycle.ctx, Format.TEXT));
  }

  // --- domain chain ----------------------------------------------------------------------------

  @Test
  void domainChainBinaryDecodeThrows() throws Throwable {
    // A chain of domains over domains, innermost base int4. Domains forward the wire bytes unchanged,
    // so a bare 4-byte int is enough: the guard trips while unwrapping.
    CodecContextBuilder builder = OfflineCodecs.builder();
    int baseOid = Oid.INT4;
    PgType outer = null;
    for (int i = 0; i < BUDGET + 4; i++) {
      outer = domain("dom" + i, DOMAIN_OID_BASE + i, baseOid);
      builder.type(outer);
      baseOid = outer.getOid();
    }
    CodecContext ctx = builder.build();
    byte[] intBytes = new byte[4];
    ByteConverter.int4(intBytes, 0, 42);
    PgType outerDomain = outer;

    withDepthBudget(BUDGET, true,
        () -> Codecs.decode(WireValueSlice.binary(intBytes), outerDomain, ctx, Object.class));
  }

  // --- B6: every CodecDepth entry point, both properties, one matrix ----------------------------
  //
  // The tests above build genuinely deep or cyclic values to exercise multi-frame recursion. This
  // matrix instead hits every guarded entry point of the five delegating codecs with ONE shallow,
  // leaf-terminated call. That is enough to pin each class's own enter()/exit() pair, because enter()
  // throws before it increments and sits before the try: seeding the counter so only the path's own
  // guards remain makes its final enter() throw, and unwinds cleanly. Two properties per entry point:
  //   guardTripsOverLimit            -- past the limit the guard throws DATA_ERROR (kills a dropped enter())
  //   noCounterLeakOverRepeatedCalls -- MAX_DEPTH+1 in-limit calls never false-refuse (kills a dropped exit())
  // Each value is leaf-terminated so nothing below the target class enters. Where the entry point stacks
  // more than one guard on its own path (only multirange's canEncodeBinary* recurse into the inner range
  // codec), guardDepth records the count and the seed leaves exactly that many enters to run, so removing
  // any one of them stops the throw rather than letting a sibling guard mask it.
  //
  // Not covered here, by design: the redundant inner private guards behind a public entry point (a
  // dropped inner enter() is fully masked by the outer one, so no black-box test can distinguish it),
  // and PgSQLOutput's own depth guard on the composite SQLData *encode* path, which lives outside these
  // five codec classes.

  private static final int DOMAIN_OID = 90_401;
  private static final int RECORD_OID = 90_411;

  private static final PgType DOM_INT4 = domain("dom_int4", DOMAIN_OID, Oid.INT4);
  private static final PgType DOM_INT8 = domain("dom_int8", DOMAIN_OID + 1, Oid.INT8);
  private static final PgType DOM_FLOAT8 = domain("dom_float8", DOMAIN_OID + 2, Oid.FLOAT8);
  private static final PgType DOM_BOOL = domain("dom_bool", DOMAIN_OID + 3, Oid.BOOL);
  private static final PgType REC_I4 = composite("rec_i4", RECORD_OID, field("f1", Oid.INT4, 1));
  private static final PgType INT4RANGE = new PgType(
      TypeName.of("pg_catalog", "int4range"), "int4range", 3904, 'r', 'R', -1, 0, 0, 0)
      .withRangeSubtype(Oid.INT4);
  private static final PgType INT4MULTIRANGE = new PgType(
      TypeName.of("pg_catalog", "int4multirange"), "int4multirange", 4451, 'm', 'R', -1, 0, 0, 0)
      .withMultirangeRange(3904);
  // Array over uuid: uuid has no fast array-leaf codec and no depth guard of its own, so it routes
  // through GenericArrayLeafCodec while never entering below the leaf -- isolating that leaf's guard.
  private static final PgType UUID_ARRAY = new PgType(
      TypeName.of("pg_catalog", "_uuid"), "uuid[]", Oid.UUID_ARRAY, 'b', 'A', -1, Oid.UUID, 0, 0);

  private static final CodecContext CTX = OfflineCodecs.builder()
      .type(DOM_INT4).type(DOM_INT8).type(DOM_FLOAT8).type(DOM_BOOL).type(REC_I4)
      .type(INT4RANGE).type(INT4MULTIRANGE).build();

  // One int4-field record wire, and its Struct form, reused by the composite cases.
  private static final byte[] REC_BINARY = nestedRecordBinary(1);
  private static final Struct REC_STRUCT = nestedRecordStruct(1, REC_I4, REC_I4);
  private static final java.util.UUID[] UUID_LEAF = {new java.util.UUID(0L, 0L)};
  // Encode inputs built once with a clean counter -- never inside a measured op, or the decode's own
  // guard would fire first and misattribute the throw. Empty payloads decode no bound/inner range;
  // the canEncodeBinaryValue negotiation short-circuits an empty range/multirange before its guard, so
  // those cases need a value with a real interval to reach enter().
  private static final Object EMPTY_RANGE = decodeQuietly("empty", INT4RANGE);
  private static final Object EMPTY_MULTIRANGE = decodeQuietly("{}", INT4MULTIRANGE);
  private static final Object RANGE_1_2 = decodeQuietly("[1,2)", INT4RANGE);
  private static final Object MULTIRANGE_1_2 = decodeQuietly("{[1,2)}", INT4MULTIRANGE);

  /** One in-limit invocation of a single {@link CodecDepth} entry point; {@code toString} names the row. */
  private static final class EntryPoint {
    final String label;
    final Executable op;
    /** Number of the target class's own guards stacked on this path; the seed leaves exactly this many. */
    final int guardDepth;

    EntryPoint(String label, Executable op) {
      this(label, 1, op);
    }

    EntryPoint(String label, int guardDepth, Executable op) {
      this.label = label;
      this.guardDepth = guardDepth;
      this.op = op;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  static Stream<EntryPoint> entryPoints() {
    return Stream.of(
        // -- Domain: transparent over an int4/int8/float8/bool leaf, so only DomainCodec enters. --
        new EntryPoint("domain.canEncodeBinaryType",
            () -> DomainCodec.INSTANCE.canEncodeBinaryType(DOM_INT4, CTX)),
        new EntryPoint("domain.canEncodeBinary",
            () -> DomainCodec.INSTANCE.canEncodeBinary(42, DOM_INT4, CTX)),
        new EntryPoint("domain.canEncodeBinaryValue",
            () -> DomainCodec.INSTANCE.canEncodeBinaryValue(42, DOM_INT4, CTX)),
        new EntryPoint("domain.mayRequireQuoting",
            () -> DomainCodec.INSTANCE.mayRequireQuoting(DOM_INT4, CTX)),
        new EntryPoint("domain.decodeBinary",
            () -> DomainCodec.INSTANCE.decodeBinary(i4(), 0, 4, DOM_INT4, CTX)),
        new EntryPoint("domain.encodeBinary",
            () -> DomainCodec.INSTANCE.encodeBinary(42, DOM_INT4, CTX)),
        new EntryPoint("domain.encodeBinary.stream",
            () -> DomainCodec.INSTANCE.encodeBinary(42, DOM_INT4, CTX, new BackpatchingByteArrayOutputStream())),
        new EntryPoint("domain.decodeText",
            () -> DomainCodec.INSTANCE.decodeText("42", DOM_INT4, CTX)),
        new EntryPoint("domain.encodeText",
            () -> DomainCodec.INSTANCE.encodeText(42, DOM_INT4, CTX)),
        new EntryPoint("domain.encodeText.appendable",
            () -> DomainCodec.INSTANCE.encodeText(42, DOM_INT4, CTX, new StringBuilder())),
        new EntryPoint("domain.decodeBinaryAs",
            () -> DomainCodec.INSTANCE.decodeBinaryAs(i4(), 0, 4, DOM_INT4, Integer.class, CTX)),
        new EntryPoint("domain.decodeTextAs",
            () -> DomainCodec.INSTANCE.decodeTextAs("42", DOM_INT4, Integer.class, CTX)),
        new EntryPoint("domain.decodeAsInt.binary",
            () -> DomainCodec.INSTANCE.decodeAsInt(i4(), 0, 4, DOM_INT4, CTX)),
        new EntryPoint("domain.decodeAsLong.binary",
            () -> DomainCodec.INSTANCE.decodeAsLong(i8(), 0, 8, DOM_INT8, CTX)),
        new EntryPoint("domain.decodeAsFloat.binary",
            () -> DomainCodec.INSTANCE.decodeAsFloat(f8(), 0, 8, DOM_FLOAT8, CTX)),
        new EntryPoint("domain.decodeAsDouble.binary",
            () -> DomainCodec.INSTANCE.decodeAsDouble(f8(), 0, 8, DOM_FLOAT8, CTX)),
        new EntryPoint("domain.decodeAsBoolean.binary",
            () -> DomainCodec.INSTANCE.decodeAsBoolean(boolByte(), 0, 1, DOM_BOOL, CTX)),
        new EntryPoint("domain.decodeAsBigDecimal.binary",
            () -> DomainCodec.INSTANCE.decodeAsBigDecimal(i8(), 0, 8, DOM_INT8, CTX)),
        new EntryPoint("domain.decodeAsString.binary",
            () -> DomainCodec.INSTANCE.decodeAsString(i4(), 0, 4, DOM_INT4, CTX)),
        new EntryPoint("domain.decodeAsInt.text",
            () -> DomainCodec.INSTANCE.decodeAsInt("42", DOM_INT4, CTX)),
        new EntryPoint("domain.decodeAsLong.text",
            () -> DomainCodec.INSTANCE.decodeAsLong("42", DOM_INT4, CTX)),
        new EntryPoint("domain.decodeAsFloat.text",
            () -> DomainCodec.INSTANCE.decodeAsFloat("42", DOM_INT4, CTX)),
        new EntryPoint("domain.decodeAsDouble.text",
            () -> DomainCodec.INSTANCE.decodeAsDouble("42", DOM_INT4, CTX)),
        new EntryPoint("domain.decodeAsBoolean.text",
            () -> DomainCodec.INSTANCE.decodeAsBoolean("t", DOM_BOOL, CTX)),
        new EntryPoint("domain.decodeAsBigDecimal.text",
            () -> DomainCodec.INSTANCE.decodeAsBigDecimal("42", DOM_INT4, CTX)),
        new EntryPoint("domain.decodeAsString.text",
            () -> DomainCodec.INSTANCE.decodeAsString("42", DOM_INT4, CTX)),

        // -- Range: int4 bounds are leaves, so only RangeCodec enters. --
        new EntryPoint("range.canEncodeBinaryType",
            () -> RangeCodec.INSTANCE.canEncodeBinaryType(INT4RANGE, CTX)),
        new EntryPoint("range.canEncodeBinaryValue",
            () -> RangeCodec.INSTANCE.canEncodeBinaryValue(RANGE_1_2, INT4RANGE, CTX)),
        new EntryPoint("range.decodeText",
            () -> RangeCodec.INSTANCE.decodeText("[1,2)", INT4RANGE, CTX)),
        new EntryPoint("range.decodeBinary",
            () -> RangeCodec.INSTANCE.decodeBinary(rangeEmptyBinary(), 0, 1, INT4RANGE, CTX)),
        new EntryPoint("range.encodeBinary",
            () -> RangeCodec.INSTANCE.encodeBinary(EMPTY_RANGE, INT4RANGE, CTX)),

        // -- Multirange: canEncodeBinary* recurse into the inner range codec, so those two stack two
        //    guards (multirange + range); decode/encode of an empty multirange has no inner range. --
        new EntryPoint("multirange.canEncodeBinaryType", 2,
            () -> MultirangeCodec.INSTANCE.canEncodeBinaryType(INT4MULTIRANGE, CTX)),
        new EntryPoint("multirange.canEncodeBinaryValue", 2,
            () -> MultirangeCodec.INSTANCE.canEncodeBinaryValue(MULTIRANGE_1_2, INT4MULTIRANGE, CTX)),
        new EntryPoint("multirange.decodeText",
            () -> MultirangeCodec.INSTANCE.decodeText("{}", INT4MULTIRANGE, CTX)),
        new EntryPoint("multirange.decodeBinary",
            () -> MultirangeCodec.INSTANCE.decodeBinary(multirangeEmptyBinary(), 0, 4, INT4MULTIRANGE, CTX)),
        new EntryPoint("multirange.encodeBinary",
            () -> MultirangeCodec.INSTANCE.encodeBinary(EMPTY_MULTIRANGE, INT4MULTIRANGE, CTX)),

        // -- Composite: a single int4 field, so no field codec enters below the composite guard. --
        new EntryPoint("composite.canEncodeBinaryType",
            () -> CompositeCodec.INSTANCE.canEncodeBinaryType(REC_I4, CTX)),
        new EntryPoint("composite.canEncodeBinaryValue",
            () -> CompositeCodec.INSTANCE.canEncodeBinaryValue(REC_STRUCT, REC_I4, CTX)),
        new EntryPoint("composite.decodeBinaryAs.struct",
            () -> CompositeCodec.INSTANCE.decodeBinaryAs(REC_BINARY, 0, REC_BINARY.length, REC_I4, Struct.class, CTX)),
        new EntryPoint("composite.decodeTextAs.struct",
            () -> CompositeCodec.INSTANCE.decodeTextAs("(1)", REC_I4, Struct.class, CTX)),
        new EntryPoint("composite.decodeBinaryAs.sqlData",
            () -> CompositeCodec.INSTANCE.decodeBinaryAs(REC_BINARY, 0, REC_BINARY.length, REC_I4, IntBox.class, CTX)),
        new EntryPoint("composite.decodeTextAs.sqlData",
            () -> CompositeCodec.INSTANCE.decodeTextAs("(1)", REC_I4, IntBox.class, CTX)),
        new EntryPoint("composite.encodeBinary",
            () -> CompositeCodec.INSTANCE.encodeBinary(REC_STRUCT, REC_I4, CTX)),
        new EntryPoint("composite.encodeText",
            () -> CompositeCodec.INSTANCE.encodeText(REC_STRUCT, REC_I4, CTX)),

        // -- Generic array leaf: a one-element uuid[] routes through GenericArrayLeafCodec, never below. --
        new EntryPoint("arrayLeaf.readLeaf",
            () -> Codecs.decode(WireValueSlice.binary(uuidArrayBinary()), UUID_ARRAY, CTX, Object.class)),
        new EntryPoint("arrayLeaf.readLeafText",
            () -> Codecs.decode(WireValueSlice.text(EMPTY_UUID_ARRAY_TEXT), UUID_ARRAY, CTX, Object.class)),
        new EntryPoint("arrayLeaf.writeLeaf",
            () -> Codecs.encode(UUID_LEAF, UUID_ARRAY, CTX, Format.BINARY)),
        new EntryPoint("arrayLeaf.appendLeaf",
            () -> Codecs.encode(UUID_LEAF, UUID_ARRAY, CTX, Format.TEXT)));
  }

  @ParameterizedTest(name = "guard trips over limit: {0}")
  @MethodSource("entryPoints")
  void guardTripsOverLimit(EntryPoint ep) throws Throwable {
    // Seed the counter so only this path's own guards remain, so its final enter() throws. Removing any
    // one of them leaves the limit uncrossed and the call succeeds -- failing the test. withDepthBudget
    // also asserts the counter unwound back to the seed.
    withDepthBudget(ep.guardDepth - 1, true, ep.op);
  }

  @ParameterizedTest(name = "no counter leak: {0}")
  @MethodSource("entryPoints")
  void noCounterLeakOverRepeatedCalls(EntryPoint ep) throws Throwable {
    // From a clean counter, MAX_DEPTH+1 successful in-limit calls on one thread. A dropped exit() would
    // climb the counter one per call and false-refuse around call MAX_DEPTH.
    for (int i = 0; i <= CodecDepth.MAX_DEPTH; i++) {
      ep.op.execute();
    }
    assertEquals(0, CodecDepth.current(), "balanced enter/exit must return the counter to 0");
  }

  // --- shallow value builders for the matrix ---------------------------------------------------

  private static final byte[] EMPTY_UUID_ARRAY_TEXT =
      "{00000000-0000-0000-0000-000000000000}".getBytes(StandardCharsets.UTF_8);

  private static byte[] i4() {
    byte[] b = new byte[4];
    ByteConverter.int4(b, 0, 42);
    return b;
  }

  private static byte[] i8() {
    byte[] b = new byte[8];
    ByteConverter.int8(b, 0, 42L);
    return b;
  }

  private static byte[] f8() {
    byte[] b = new byte[8];
    ByteConverter.float8(b, 0, 42.0);
    return b;
  }

  /** A one-byte bool wire holding true. */
  private static byte[] boolByte() {
    return new byte[]{1};
  }

  /** The one-byte binary image of an empty range: the lone flags byte with the empty bit set. */
  private static byte[] rangeEmptyBinary() {
    return new byte[]{0x01}; // RangeCodec.FLAG_EMPTY
  }

  /** The four-byte binary image of an empty multirange: a range count of zero. */
  private static byte[] multirangeEmptyBinary() {
    return new byte[4];
  }

  /** A one-dimensional, one-element {@code uuid[]} binary wire holding the all-zero UUID. */
  private static byte[] uuidArrayBinary() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    putInt(out, 1);          // dimensions
    putInt(out, 0);          // hasNulls
    putInt(out, Oid.UUID);   // element oid
    putInt(out, 1);          // dimension length
    putInt(out, 1);          // lower bound
    putInt(out, 16);         // element length
    out.write(new byte[16], 0, 16); // 00000000-0000-0000-0000-000000000000
    return out.toByteArray();
  }

  /** Decodes a range/multirange literal once, off the measured path, to seed the encode cases. */
  private static Object decodeQuietly(String literal, PgType type) {
    try {
      Object value = type.getTyptype() == 'm'
          ? MultirangeCodec.INSTANCE.decodeText(literal, type, CTX)
          : RangeCodec.INSTANCE.decodeText(literal, type, CTX);
      if (value == null) {
        throw new AssertionError("decode returned null for " + literal);
      }
      return value;
    } catch (SQLException e) {
      throw new AssertionError("failed to build encode input from " + literal, e);
    } finally {
      CodecDepth.clear();
    }
  }

  /** Minimal {@link SQLData} over the {@code rec_i4} composite, to reach the SQLData decode branches. */
  public static final class IntBox implements java.sql.SQLData {
    private int value;

    @Override
    public String getSQLTypeName() {
      return "rec_i4";
    }

    @Override
    public void readSQL(java.sql.SQLInput stream, String typeName) throws SQLException {
      value = stream.readInt();
    }

    @Override
    public void writeSQL(java.sql.SQLOutput stream) throws SQLException {
      stream.writeInt(value);
    }
  }

  // --- depth-budget harness --------------------------------------------------------------------

  /**
   * Pre-seeds the shared depth counter so only {@code budget} further levels are allowed, runs
   * {@code op}, then asserts it behaved as {@code expectThrow} says and that the codec unwound its own
   * enters back to the seed (a leaked counter would poison later codec calls on this thread).
   */
  private void withDepthBudget(int budget, boolean expectThrow, Executable op) throws Throwable {
    int seed = CodecDepth.MAX_DEPTH - budget;
    for (int i = 0; i < seed; i++) {
      CodecDepth.enter();
    }
    try {
      if (expectThrow) {
        PSQLException ex = assertThrows(PSQLException.class, op);
        assertEquals(PSQLState.DATA_ERROR.getState(), ex.getSQLState(), "SQLState");
        assertTrue(ex.getMessage().contains("nesting depth"),
            "expected a nesting-depth error, got: " + ex.getMessage());
      } else {
        op.execute();
      }
      assertEquals(seed, CodecDepth.current(),
          "codec must unwind its depth enters back to the pre-seeded level");
    } finally {
      CodecDepth.clear();
    }
  }

  // --- record payload / value builders ---------------------------------------------------------

  /** Builds {@code record(record(... record(int4) ...))} {@code depth} levels deep as binary. */
  private static byte[] nestedRecordBinary(int depth) {
    ByteArrayOutputStream leaf = new ByteArrayOutputStream();
    putInt(leaf, 1);         // nfields
    putInt(leaf, Oid.INT4);  // field type oid
    putInt(leaf, 4);         // field length
    putInt(leaf, 1);         // int4 value
    byte[] payload = leaf.toByteArray();
    for (int i = 1; i < depth; i++) {
      ByteArrayOutputStream wrap = new ByteArrayOutputStream();
      putInt(wrap, 1);            // nfields
      putInt(wrap, Oid.RECORD);   // field type oid = record
      putInt(wrap, payload.length);
      wrap.write(payload, 0, payload.length);
      payload = wrap.toByteArray();
    }
    return payload;
  }

  private static void putInt(ByteArrayOutputStream out, int value) {
    out.write((value >>> 24) & 0xff);
    out.write((value >>> 16) & 0xff);
    out.write((value >>> 8) & 0xff);
    out.write(value & 0xff);
  }

  /** Builds a {@code depth}-deep nested {@link PgStruct} graph, innermost a single int4. */
  private static PgStruct nestedRecordStruct(int depth, PgType recordType, PgType leafType) {
    PgStruct value = new PgStruct(leafType, new Object[]{1}, null);
    for (int i = 1; i < depth; i++) {
      value = new PgStruct(recordType, new Object[]{value}, null);
    }
    return value;
  }

  // --- array <-> composite cycle ---------------------------------------------------------------

  private static final int CYCLE_RECORD_OID = 90_101;
  private static final int CYCLE_ARRAY_OID = 90_102;
  private static final int DOMAIN_OID_BASE = 90_201;

  private static final class Cycle {
    final PgType recordType;
    final CodecContext ctx;

    Cycle(PgType recordType, CodecContext ctx) {
      this.recordType = recordType;
      this.ctx = ctx;
    }
  }

  /** A composite whose only field is an array of that same composite: {@code rec(rec[])}. */
  private static Cycle arrayCompositeCycle() {
    PgType recordType = composite("cyc", CYCLE_RECORD_OID, field("f1", CYCLE_ARRAY_OID, 1));
    PgType arrayType = new PgType(TypeName.of("public", "_cyc"), "public._cyc", CYCLE_ARRAY_OID,
        'b', 'A', -1, CYCLE_RECORD_OID, 0, 0);
    CodecContext ctx = OfflineCodecs.builder().type(recordType).type(arrayType).build();
    return new Cycle(recordType, ctx);
  }

  /**
   * Builds {@code cycles} nesting steps of {@code rec([rec([... rec([]) ...])])}. Each step adds a
   * composite and an array level, so the depth grows twice as fast as {@code cycles} — comfortably
   * past any small budget.
   */
  private static PgStruct arrayCompositeCycleValue(int cycles, Cycle cycle) {
    // Innermost: a record whose array field is empty, terminating the recursion.
    PgStruct value = new PgStruct(cycle.recordType, new Object[]{new Object[0]}, null);
    for (int i = 0; i < cycles; i++) {
      Object[] array = new Object[]{value};
      value = new PgStruct(cycle.recordType, new Object[]{array}, null);
    }
    return value;
  }

  // --- type helpers (mirroring OfflineContainerRoundtripTest) -----------------------------------

  private static PgField field(String name, int oid, int position) {
    return new PgField(name, oid, position, -1);
  }

  private static PgType composite(String simpleName, int oid, PgField... fields) {
    return new PgType(TypeName.of("public", simpleName), "public." + simpleName, oid, 'c', 'C',
        -1, 0, 0, 0, ',', Arrays.asList(fields));
  }

  private static PgType anonymousRecord(PgField... fields) {
    return new PgType(TypeName.of("pg_catalog", "record"), "record", Oid.RECORD, 'c', 'C',
        -1, 0, 0, 0, ',', Arrays.asList(fields));
  }

  private static PgType domain(String simpleName, int oid, int baseOid) {
    return new PgType(TypeName.of("public", simpleName), "public." + simpleName, oid, 'd', 'N',
        -1, 0, 0, baseOid, null);
  }
}
