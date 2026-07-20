/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.api.codec.WireValueSlice;
import org.postgresql.core.BaseConnection;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.util.PGBinaryObject;
import org.postgresql.util.PGobject;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Server-truth oracle for the binary codecs: the server, not a round-trip, decides whether the
 * driver's encoding and decoding are correct.
 *
 * <p>{@link CodecParityRoundtripTest} pins {@code binary-decode == text-decode == original}. That
 * catches divergence between the two wire formats, but the server is only an implicit arbiter: a
 * matched encoder/decoder bug (the encoder writes a wrong value, the decoder reads it back to
 * {@code original}) survives green, and temporal types degrade to a pure {@code text == binary}
 * check with no known-good answer at all. This oracle removes both blind spots by asking the server
 * directly.</p>
 *
 * <h2>Encode truth</h2>
 *
 * <p>{@link #assertEncodeTruth} takes the bytes the codec's binary encoder produces (offline, via
 * {@link Codecs#encode}, the same entry the fuzzers exercise), sends them as a typed <b>binary</b>
 * parameter {@code $1}, and compares the server's {@code $1::text} against the server's
 * {@code $2::t::text} of an independent literal bound over the {@code unknown} type (so the server
 * parses it with the type's own input function). The driver's decoder never enters the loop, and the
 * comparison is over the server's canonical text, which is deliberately strict: it preserves a
 * {@code numeric} scale, normalises a {@code box}, and carries a {@code timestamptz} offset, so a
 * dscale, corner-order, or offset bug shows up as unequal text.</p>
 *
 * <h2>Decode truth</h2>
 *
 * <p>{@link #assertDecodeTruth} sends the same independent literal, receives the value in binary, and
 * compares the driver's {@code getString} rendering against the server's {@code ::text}. Intentional
 * divergences (a driver that renders an offset differently by design) are the caller's to allow.</p>
 */
final class ServerTruthOracle {

  private ServerTruthOracle() {
  }

  /**
   * Asserts that the codec's binary encoding of {@code value} as {@code oid} means, to the server,
   * exactly the type's own parse of {@code literal}.
   *
   * @param con a connection; binary send for {@code oid} is enabled here if it is not already
   * @param oid the type OID
   * @param typeName the type's {@code pg_type.typname} (e.g. {@code "numeric"})
   * @param value the Java value to run through the codec's binary encoder
   * @param literal the independent canonical PostgreSQL text literal for the same value
   */
  static void assertEncodeTruth(Connection con, int oid, String typeName, Object value,
      String literal) throws SQLException {
    BaseConnection base = con.unwrap(BaseConnection.class);
    // Force the type into the binary send set so the PGBinaryObject path below actually ships our
    // pre-encoded bytes rather than silently falling back to text (setPGobject's else branch).
    base.getQueryExecutor().addBinarySendOid(oid);
    assertTrue(base.binaryTransferSend(oid),
        () -> "binary send must be enabled for " + typeName + " (oid " + oid + ")");

    byte[] encoded = encodeBinary(base, oid, value);
    RawBinaryParam param = new RawBinaryParam(encoded, typeName);

    // The first ? carries the type OID (from the PGBinaryObject) and travels binary; the second is
    // the literal bound as unknown (Types.OTHER -> Oid.UNSPECIFIED) and cast to the type by the
    // server's input function.
    String sql = "SELECT (?)::text, (?::" + typeName + ")::text";
    try (PreparedStatement ps = con.prepareStatement(sql)) {
      ps.setObject(1, param);
      ps.setObject(2, literal, Types.OTHER);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), () -> "no row for [" + sql + "]");
        String encodedText = rs.getString(1);
        String literalText = rs.getString(2);
        assertEquals(literalText, encodedText,
            () -> "encode-truth for " + typeName + " value=" + value + " literal=" + literal
                + ": server text of the binary encoding must equal the server text of the literal");
      }
    }
  }

  /**
   * Asserts that the driver's {@code getString} of a binary-received value equals the server's
   * {@code ::text} of the same value.
   *
   * @param con a connection that receives {@code oid} in binary
   * @param oid the type OID
   * @param typeName the type's {@code pg_type.typname}
   * @param literal the independent canonical PostgreSQL text literal
   */
  static void assertDecodeTruth(Connection con, int oid, String typeName, String literal)
      throws SQLException {
    BaseConnection base = con.unwrap(BaseConnection.class);
    base.getQueryExecutor().addBinaryReceiveOid(oid);
    assertTrue(base.getQueryExecutor().useBinaryForReceive(oid),
        () -> "binary receive must be enabled for " + typeName + " (oid " + oid + ")");

    String sql = "SELECT ?::" + typeName + ", (?::" + typeName + ")::text";
    try (PreparedStatement ps = con.prepareStatement(sql)) {
      ps.setObject(1, literal, Types.OTHER);
      ps.setObject(2, literal, Types.OTHER);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), () -> "no row for [" + sql + "]");
        String driverText = rs.getString(1);
        String serverText = rs.getString(2);
        assertEquals(serverText, driverText,
            () -> "decode-truth for " + typeName + " literal=" + literal
                + ": driver getString must equal the server ::text");
      }
    }
  }

  /**
   * Pins a <em>known</em> divergence between the driver's {@code getString} and the server's
   * {@code ::text}: the driver renders {@code expectedDriverText}, the server renders
   * {@code expectedServerText}, and the two differ. This documents a deliberate gap (a
   * {@link PGobject} type whose {@code getValue()} is the driver's own canonical form, not the
   * server's) and fails if either side changes, so a silent shift is caught.
   *
   * @param con a connection that receives {@code oid} in binary
   * @param oid the type OID
   * @param typeName the type's {@code pg_type.typname}
   * @param literal the independent canonical PostgreSQL text literal
   * @param expectedDriverText the driver's current {@code getString} rendering
   * @param expectedServerText the server's current {@code ::text} rendering
   */
  static void assertDecodeDivergence(Connection con, int oid, String typeName, String literal,
      String expectedDriverText, String expectedServerText) throws SQLException {
    BaseConnection base = con.unwrap(BaseConnection.class);
    base.getQueryExecutor().addBinaryReceiveOid(oid);

    String sql = "SELECT ?::" + typeName + ", (?::" + typeName + ")::text";
    try (PreparedStatement ps = con.prepareStatement(sql)) {
      ps.setObject(1, literal, Types.OTHER);
      ps.setObject(2, literal, Types.OTHER);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), () -> "no row for [" + sql + "]");
        String driverText = rs.getString(1);
        String serverText = rs.getString(2);
        assertEquals(expectedServerText, serverText,
            () -> "server ::text for " + typeName + " literal=" + literal + " changed");
        assertEquals(expectedDriverText, driverText,
            () -> "driver getString for " + typeName + " literal=" + literal + " changed");
        assertNotEquals(serverText, driverText,
            () -> "expected a known driver/server rendering divergence for " + typeName
                + ", but they now agree -- promote this to assertDecodeTruth");
      }
    }
  }

  /**
   * Asserts that the driver reads a container <em>text</em> literal to the value the type's own
   * input function reads it to.
   *
   * <p>Aimed at the container grammars, where the driver reimplements {@code array_in},
   * {@code record_in} and {@code range_parse_bound} rather than delegating: whitespace inside a
   * field, a value split across quoted and unquoted segments, an escape outside quotes, an empty
   * field versus a whitespace-only one. Server output never exercises any of it, since the output
   * functions quote whatever would be ambiguous, so only a hand-written literal reaches these paths
   * ({@link PGobject#setValue}, {@code PGRange(String)}, an offline decode, a text {@code COPY}).</p>
   *
   * <p>The comparison runs through the server twice: the driver's parse is re-encoded and shipped
   * back as a binary parameter, and its {@code ::text} is compared against the {@code ::text} of the
   * server's own parse of the literal. That reuses {@link #assertEncodeTruth}'s comparator instead of
   * inventing a second canonical form. A failure therefore means the parse or the re-encode is
   * wrong; the encoders are covered independently by the encode-truth cases, so suspect the parse
   * first.</p>
   *
   * @param con a connection; binary send for {@code oid} is enabled here if it is not already
   * @param oid the type OID
   * @param typeName the type's {@code pg_type.typname}
   * @param literal the text literal to parse, as a caller would hand it to the driver
   */
  static void assertTextParseTruth(Connection con, int oid, String typeName, String literal)
      throws SQLException {
    Object parsed = parseText(con, oid, literal);
    assertNotNull(parsed,
        () -> "text-parse truth for " + typeName + " literal=" + literal
            + ": the driver read the literal as SQL NULL");
    assertEncodeTruth(con, oid, typeName, parsed, literal);
  }

  /** What the driver does with a literal the server's own input function refuses. */
  enum DriverVerdict {
    /** The driver refuses it too. */
    REFUSES,
    /**
     * The driver accepts it by design. {@link LiteralCursor} is deliberately lenient on decode —
     * it takes {@code ""} inside quotes as an escape and concatenates segments that
     * {@code array_in} would reject — which only ever widens what decodes. It never changes the
     * value of a literal the server itself accepts.
     */
    ACCEPTS_BY_DESIGN
  }

  /**
   * Pins that the server still refuses {@code literal}, and what the driver does with it.
   *
   * <p>A grammar the driver reimplements is pinned against the server on the accepting side by
   * {@link #assertTextParseTruth}, but that says nothing about inputs PostgreSQL rejects. Recording
   * only "we match today's server on what it accepts" would let a future server quietly start
   * accepting one of these — {@code array_in} growing the segment concatenation {@code record_in}
   * already has, say — and leave the driver's reading of it unexamined. Each case here fails the
   * moment the server's verdict changes, so the pair is re-judged against a real value rather than
   * against what was true when it was written.</p>
   *
   * @param con a connection
   * @param oid the type OID, for the driver's own parse
   * @param typeName the type's {@code pg_type.typname}
   * @param literal the literal the server is expected to refuse
   * @param expectedSqlState the SQLState of the server's refusal
   * @param driver what the driver is expected to do with the same literal
   */
  static void assertServerRefuses(Connection con, int oid, String typeName, String literal,
      String expectedSqlState, DriverVerdict driver) throws SQLException {
    SQLException refusal = null;
    try (PreparedStatement ps = con.prepareStatement("SELECT ?::" + typeName)) {
      ps.setObject(1, literal, Types.OTHER);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
      }
    } catch (SQLException e) {
      refusal = e;
    }
    SQLException serverRefusal = refusal;
    assertNotNull(serverRefusal,
        () -> "the server now accepts " + typeName + " literal=" + literal
            + " -- re-judge this case against the value it produces and, if the driver agrees,"
            + " move it to assertTextParseTruth");
    assertEquals(expectedSqlState, serverRefusal.getSQLState(),
        () -> "the server still refuses " + typeName + " literal=" + literal
            + ", but under a different SQLState");

    SQLException driverRefusal = null;
    try {
      parseText(con, oid, literal);
    } catch (SQLException e) {
      driverRefusal = e;
    }
    SQLException fromDriver = driverRefusal;
    if (driver == DriverVerdict.REFUSES) {
      assertNotNull(fromDriver,
          () -> "the driver accepts " + typeName + " literal=" + literal
              + ", which the server refuses; either fix the parse or record the leniency"
              + " as ACCEPTS_BY_DESIGN");
    } else {
      assertNull(fromDriver,
          () -> "the driver now refuses " + typeName + " literal=" + literal
              + ", which it used to accept by design; if that is intended, switch this case"
              + " to REFUSES: " + (fromDriver == null ? "" : fromDriver.getMessage()));
    }
  }

  /**
   * Decodes {@code literal} through the type's text codec, as the driver would.
   *
   * <p>Both container decodes hand back something that has not read the literal yet: an array
   * becomes a lazy {@link java.sql.Array} on a connection-bound context, and a composite becomes a
   * {@link PGobject} still holding the raw text. Each is forced here — the array by materializing
   * it, the composite by the binary encode that has to parse it. Without that, a literal the
   * grammar should refuse looks accepted simply because nothing has read it yet.</p>
   */
  private static @Nullable Object parseText(Connection con, int oid, String literal)
      throws SQLException {
    BaseConnection base = con.unwrap(BaseConnection.class);
    PgCodecContext ctx = base.getCodecContext();
    TypeDescriptor type = ctx.resolveType(oid);
    Object decoded = Codecs.decode(WireValueSlice.text(literal.getBytes(ctx.getCharset())), type,
        ctx, Object.class);
    if (decoded == null) {
      return null;
    }
    Object materialized = decoded instanceof Array ? ((Array) decoded).getArray() : decoded;
    Codecs.encode(materialized, type, ctx, Format.BINARY);
    return materialized;
  }

  /** Encodes {@code value} as {@code oid} in binary via the connection's own codec context. */
  private static byte[] encodeBinary(BaseConnection base, int oid, Object value)
      throws SQLException {
    PgCodecContext ctx = base.getCodecContext();
    TypeDescriptor type = ctx.resolveType(oid);
    return Codecs.encode(value, type, ctx, Format.BINARY).toByteArray();
  }

  /**
   * A {@link PGobject} that ships a fixed byte array as a binary parameter for a chosen type. The
   * {@link PGBinaryObject} contract is the only reflection-free way for a test to bind raw binary
   * bytes with a specific OID; the driver looks the OID up from {@link #getType()} and, when binary
   * send is enabled, calls {@link #toBytes} instead of {@link #getValue()}.
   */
  private static final class RawBinaryParam extends PGobject implements PGBinaryObject {
    private final byte[] bytes;

    RawBinaryParam(byte[] bytes, String typeName) {
      this.bytes = bytes;
      setType(typeName);
    }

    @Override
    public int lengthInBytes() {
      return bytes.length;
    }

    @Override
    public void toBytes(byte[] target, int offset) {
      System.arraycopy(bytes, 0, target, offset, bytes.length);
    }

    @Override
    public void setByteValue(byte[] value, int offset) {
      throw new UnsupportedOperationException("RawBinaryParam is send-only");
    }
  }
}
