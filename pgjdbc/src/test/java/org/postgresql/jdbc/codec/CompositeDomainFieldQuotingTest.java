/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.TypeName;
import org.postgresql.api.codec.WireValueSlice;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.OfflineCodecs;
import org.postgresql.jdbc.PgField;
import org.postgresql.jdbc.PgStruct;
import org.postgresql.jdbc.PgType;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Struct;
import java.util.Arrays;

/**
 * Pins how a composite renders a domain-typed field. A domain is transparent to its base type, so a
 * domain over a quote-safe base ({@code int4}, {@code numeric}) must render unquoted inside a record
 * literal, exactly as the plain base type would -- {@code (5,...)}, never {@code ("5",...)}. Before
 * the fix the shared {@link DomainCodec} singleton inherited the pessimistic default
 * {@link org.postgresql.api.codec.TextCodec#mayRequireQuoting()} of {@code true}, so every domain
 * field was quoted; the artifact surfaced through the differential compat oracle as {@code ("5",,plain)}
 * where the server and the released baseline write {@code (5,,plain)}.
 *
 * <p>The reciprocal case guards against over-correcting into under-quoting: a domain over {@code text}
 * still needs quoting when its value carries a comma or a quote, so it must stay quoted.
 */
class CompositeDomainFieldQuotingTest {

  private static final PgType POSINT =
      new PgType(TypeName.of("compat_udt", "posint"), "compat_udt.posint",
          90_010, 'd', 'N', -1, 0, 0, Oid.INT4);

  /** A domain over {@code text}, used to prove a text-based domain field is still quoted when needed. */
  private static final PgType TEXTDOM =
      new PgType(TypeName.of("compat_udt", "textdom"), "compat_udt.textdom",
          90_020, 'd', 'S', -1, 0, 0, Oid.TEXT);

  private static PgField field(String name, int oid, int position) {
    return new PgField(name, oid, position, -1);
  }

  private static final PgType DCOMP = new PgType(
      TypeName.of("compat_udt", "dcomp"), "compat_udt.dcomp", 90_011, 'c', 'C',
      -1, 0, 0, 0, ',',
      Arrays.asList(field("a", POSINT.getOid(), 1), field("b", Oid.NUMERIC, 2), field("c", Oid.TEXT, 3)));

  private static final PgType TCOMP = new PgType(
      TypeName.of("compat_udt", "tcomp"), "compat_udt.tcomp", 90_021, 'c', 'C',
      -1, 0, 0, 0, ',',
      Arrays.asList(field("d", TEXTDOM.getOid(), 1), field("x", Oid.INT4, 2)));

  /** Encodes the struct in binary, decodes it back, and returns the rebuilt {@code record_out} literal. */
  private static String roundTripLiteral(PgType type, Object[] attributes) throws SQLException {
    CodecContext ctx = OfflineCodecs.builder().type(POSINT).type(TEXTDOM).type(DCOMP).type(TCOMP).build();
    WireValueSlice raw = Codecs.encode(new PgStruct(type, attributes, null), type, ctx, Format.BINARY);
    PgStruct decoded = (PgStruct) Codecs.decode(raw, type, ctx, Struct.class);
    assertNotNull(decoded, "binary composite decode");
    String literal = decoded.getValue();
    assertNotNull(literal, "rebuilt composite literal");
    return literal;
  }

  @Test
  void intDomainFieldIsUnquotedWhenSiblingIsNull() throws SQLException {
    // The reported bug: the int domain field 'a' was quoted only because a later field was NULL.
    assertEquals("(5,,plain)", roundTripLiteral(DCOMP, new Object[]{5, null, "plain"}));
  }

  @Test
  void intDomainFieldIsUnquotedWhenAllFieldsPresent() throws SQLException {
    assertEquals("(5,12345.67,hi)",
        roundTripLiteral(DCOMP, new Object[]{5, new BigDecimal("12345.67"), "hi"}));
  }

  @Test
  void intDomainFieldStaysUnquotedWhileASiblingIsQuoted() throws SQLException {
    // The quoted text sibling must not drag quoting onto the quote-safe domain field.
    assertEquals("(5,12345.67,\"a,b\"\"c\\\\d\")",
        roundTripLiteral(DCOMP, new Object[]{5, new BigDecimal("12345.67"), "a,b\"c\\d"}));
  }

  @Test
  void textDomainFieldIsStillQuotedWhenItsValueNeedsIt() throws SQLException {
    // A domain over text is not quote-safe: a comma-bearing value must remain quoted, so the fix
    // delegates to the base type's quoting need rather than blanket-disabling it.
    assertEquals("(\"a,b\",1)", roundTripLiteral(TCOMP, new Object[]{"a,b", 1}));
  }
}
