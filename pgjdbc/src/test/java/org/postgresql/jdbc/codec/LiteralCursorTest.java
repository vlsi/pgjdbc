/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.CharBuffer;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests {@link LiteralCursor}'s element scanning and single-level un-escaping.
 *
 * <p>The cursor peels exactly one level of quoting/escaping per pass. Nested
 * containers ({@code row(row(...))}, {@code array-of-composite}) are handled by
 * recursion: each level dispatches the peeled slice to a child codec that
 * re-parses it with a fresh cursor, so the cursor itself never tracks nesting
 * depth. These tests pin both the single-level behaviour and that one peel
 * leaves the inner literal intact for the next level.</p>
 */
class LiteralCursorTest {

  /** Parses a one-dimensional array literal {@code {...}} into its peeled elements. */
  private static List<String> array(String literal) throws SQLException {
    return array(literal, ',');
  }

  /** Parses a one-dimensional array literal with a non-default delimiter. */
  private static List<String> array(String literal, char delim) throws SQLException {
    LiteralCursor c = LiteralCursor.over(literal);
    c.skipDimensionPrefix();
    c.expect('{');
    List<String> out = new ArrayList<>();
    if (c.tryConsume('}')) {
      return out;
    }
    do {
      c.readValue(delim, '}');
      // The array NULL marker, as in the leaf codecs: an unquoted NULL only.
      out.add(!c.tokenWasQuoted() && c.tokenEquals("NULL") ? null : c.getToken().toString());
    } while (c.tryConsume(delim));
    c.expect('}');
    return out;
  }

  /** Parses a composite literal {@code (...)} into its peeled fields. */
  private static List<String> composite(String literal) throws SQLException {
    LiteralCursor c = LiteralCursor.over(literal);
    c.expect('(');
    List<String> out = new ArrayList<>();
    do {
      c.readValue(',', ')');
      // The composite NULL rule, as in CompositeCodec: unquoted and empty.
      out.add(!c.tokenWasQuoted() && c.tokenLength() == 0 ? null : c.getToken().toString());
    } while (c.tryConsume(','));
    c.expect(')');
    return out;
  }

  /** Parses a range literal into its two peeled bounds, an unbounded one as {@code null}. */
  private static List<String> range(String literal) throws SQLException {
    LiteralCursor c = LiteralCursor.over(literal);
    c.expect(literal.charAt(0));
    List<String> out = new ArrayList<>();
    c.readValue(',', ']', ')');
    out.add(!c.tokenWasQuoted() && c.tokenLength() == 0 ? null : c.getToken().toString());
    c.expect(',');
    c.readValue(',', ']', ')');
    out.add(!c.tokenWasQuoted() && c.tokenLength() == 0 ? null : c.getToken().toString());
    return out;
  }

  // -------------------------- plain scanning --------------------------

  @Test
  void plainElements() throws SQLException {
    assertEquals(Arrays.asList("1", "2", "3"), array("{1,2,3}"));
  }

  @Test
  void emptyContainer() throws SQLException {
    assertEquals(Arrays.asList(), array("{}"));
  }

  @Test
  void ignoresUnquotedWhitespace() throws SQLException {
    assertEquals(Arrays.asList("1", "2"), array("{ 1 , 2 }"));
  }

  @Test
  void skipsDimensionPrefix() throws SQLException {
    assertEquals(Arrays.asList("a", "b"), array("[0:1]={a,b}"));
  }

  @Test
  void customDelimiter() throws SQLException {
    assertEquals(Arrays.asList("1", "2", "3"), array("{1;2;3}", ';'));
  }

  // -------------------------- one-level un-escaping --------------------------

  @Test
  void delimiterInsideQuotesIsNotASeparator() throws SQLException {
    assertEquals(Arrays.asList("a,b", "c", "d,e"), array("{\"a,b\",\"c\",\"d,e\"}"));
  }

  @Test
  void bracketsInsideQuotesArePreserved() throws SQLException {
    // An array element that is itself a composite literal: the inner "(1,2)" is
    // returned intact, ready for a child composite codec to re-parse.
    assertEquals(Arrays.asList("(1,2)", "(3,4)"), array("{\"(1,2)\",\"(3,4)\"}"));
  }

  @Test
  void backslashEscapedQuote() throws SQLException {
    // literal: {"c\"d"}
    assertEquals(Arrays.asList("c\"d"), array("{\"c\\\"d\"}"));
  }

  @Test
  void doubledQuote() throws SQLException {
    // literal: {"c""d"}
    assertEquals(Arrays.asList("c\"d"), array("{\"c\"\"d\"}"));
  }

  @Test
  void escapedBackslash() throws SQLException {
    // literal: {"a\\b"}
    assertEquals(Arrays.asList("a\\b"), array("{\"a\\\\b\"}"));
  }

  @Test
  void unquotedNullVersusQuotedNullString() throws SQLException {
    // unquoted NULL -> null; quoted "NULL" -> the four-character string
    assertEquals(Arrays.asList(null, "NULL", "x"), array("{NULL,\"NULL\",x}"));
  }

  // -------------------------- container-specific bracket stops --------------------------

  @Test
  void compositeFieldMayContainUnquotedBraces() throws SQLException {
    // An empty array field {} is not quoted by the server (no comma/quote/paren),
    // so it must not terminate the composite field early at '}'.
    assertEquals(Arrays.asList("a", "{}", "b"), composite("(a,{},b)"));
  }

  @Test
  void arrayElementMayBeUnquotedParen() throws SQLException {
    // A lone ')' is a valid unquoted array element; only '}' closes the array.
    assertEquals(Arrays.asList("a", ")", "b"), array("{a,),b}"));
  }

  // -------------------------- nested escaping unwinds by recursion --------------------------

  /**
   * {@code row(row('x"y'), 2)} serialises with compounded quoting. One pass peels
   * exactly one level, leaving the inner composite literal {@code ("x""y",1)}
   * still escaped for the next level — which the second pass then peels.
   */
  @Test
  void nestedComposite_peelsOneLevelPerPass() throws SQLException {
    // outer literal: ("(""x""""y"",1)",2)
    List<String> outer = composite("(\"(\"\"x\"\"\"\"y\"\",1)\",2)");
    assertEquals(Arrays.asList("(\"x\"\"y\",1)", "2"), outer);

    // the inner field, re-parsed by a fresh cursor, peels the remaining level
    List<String> inner = composite(outer.get(0));
    assertEquals(Arrays.asList("x\"y", "1"), inner);
  }

  // -------------------------- the borrowed token view --------------------------

  /** Reads every token of {@code {...}} as {@code getToken()} reports it, one at a time. */
  private static List<String> tokensOf(CharSequence literal) throws SQLException {
    LiteralCursor c = LiteralCursor.over(literal);
    c.expect('{');
    List<String> out = new ArrayList<>();
    do {
      c.readValue(',', '}');
      CharSequence token = c.getToken();
      // Read the view through CharSequence, not toString(), so a wrong length or offset shows up
      // as wrong characters rather than being hidden by the copy.
      StringBuilder sb = new StringBuilder(token.length());
      for (int i = 0; i < token.length(); i++) {
        sb.append(token.charAt(i));
      }
      assertEquals(sb.toString(), token.toString(), "charAt must agree with toString");
      out.add(sb.toString());
    } while (c.tryConsume(','));
    c.expect('}');
    return out;
  }

  @Test
  void tokenView_readsUnquotedTokensFromTheSourceBuffer() throws SQLException {
    assertEquals(Arrays.asList("1", "22", "333"), tokensOf("{1,22,333}"));
  }

  @Test
  void tokenView_readsUnescapedTokensFromTheScratchBuffer() throws SQLException {
    // literal: {"a""b","c\d"} — both need un-escaping, so both come out of scratch.
    assertEquals(Arrays.asList("a\"b", "c\\d"), tokensOf("{\"a\"\"b\",\"c\\\\d\"}"));
  }

  @Test
  void tokenView_switchesBetweenSourceAndScratch() throws SQLException {
    // plain -> escaped -> plain -> longer escaped: the view is repointed each time, and the
    // scratch buffer grows on the last token without stranding the view on the old array.
    assertEquals(Arrays.asList("x", "a\"b", "y", "long\"value\"here"),
        tokensOf("{x,\"a\"\"b\",y,\"long\"\"value\"\"here\"}"));
  }

  @Test
  void tokenView_readsAnEmbeddedCharBuffer() throws SQLException {
    // The literal sits at offset 4 of a larger array. A wrapped slice carries the offset as the
    // buffer's position; slicing it again carries the same offset as arrayOffset instead. A cursor
    // that dropped either would read the surrounding noise.
    char[] backing = "####{1,22,333}####".toCharArray();
    CharBuffer positioned = CharBuffer.wrap(backing, 4, 10);
    assertEquals(Arrays.asList("1", "22", "333"), tokensOf(positioned));

    CharBuffer sliced = positioned.slice();
    assertEquals(4, sliced.arrayOffset(), "slice must fold the offset into arrayOffset");
    assertEquals(Arrays.asList("1", "22", "333"), tokensOf(sliced));
  }

  @Test
  void tokenView_readsAReadOnlyCharBufferThroughTheFallback() throws SQLException {
    // A read-only buffer denies hasArray(), so the cursor copies the literal out once up front.
    CharBuffer readOnly = CharBuffer.wrap("{x,\"a\"\"b\"}".toCharArray()).asReadOnlyBuffer();
    assertFalse(readOnly.hasArray(), "a read-only buffer must not expose its array");
    assertEquals(Arrays.asList("x", "a\"b"), tokensOf(readOnly));
  }

  @Test
  void tokenView_exposesItsBackingArraySoANestedLiteralCostsNoCopy() throws SQLException {
    // A nested container reopens its field with LiteralCursor.over, which reads a buffer in place
    // only when it can reach the array behind it. A view that hid its array would still decode
    // correctly — it would just quietly copy every nested literal — so pin the property itself.
    char[] backing = "{alpha,beta}".toCharArray();
    LiteralCursor c = LiteralCursor.over(CharBuffer.wrap(backing));
    c.expect('{');
    c.readValue(',', '}');

    CharBuffer token = (CharBuffer) c.getToken();
    assertTrue(token.hasArray(), "the token must expose its backing array");
    assertSame(backing, token.array(), "the token must point at the source, not a copy");
    assertEquals(1, token.arrayOffset() + token.position(), "token starts after the brace");
    assertEquals("alpha", token.toString());
  }
}
