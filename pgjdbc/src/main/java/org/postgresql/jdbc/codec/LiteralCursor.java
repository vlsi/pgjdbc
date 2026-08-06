/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import java.nio.CharBuffer;
import java.sql.SQLException;

/**
 * Cursor over a PostgreSQL container text literal (array, composite, range).
 *
 * <p>Owns the two jobs that the binary length-prefixed format does not have:
 * finding where one element ends (scanning past quotes, escapes and the
 * delimiter) and stripping the quoting/escaping so the leaf codec receives the
 * logical value. The container driver supplies the structural framing (brackets,
 * delimiter, null convention); the cursor stays policy-free so a single instance
 * serves arrays, composites and ranges.</p>
 *
 * <h2>Reading values</h2>
 *
 * <p>A value is a run of quoted and unquoted segments concatenated, so {@code ab"cd"} is the
 * one value {@code abcd} and a delimiter ends the element only outside quotes. Where the
 * containers differ is whitespace, so the two entry points name the rule they follow:
 * {@link #readArrayElement} strips whitespace that sits outside quotes ({@code array_in}),
 * {@link #readVerbatim} keeps it ({@code record_in} and {@code range_parse_bound}, where
 * {@code ( x,y)} has a first field of {@code " x"}). Those two parsers strip whitespace only
 * around the literal as a whole, which is the container driver's job through
 * {@link #skipWhitespace()} and {@link #expect(char)}.</p>
 *
 * <p>{@code readArrayElement}/{@code readVerbatim} consume one element and expose it as a borrowed
 * {@link CharBuffer} via {@link #getToken()}. The view points directly into the backing
 * {@code char[]} when the element has no escapes, and into a reusable scratch buffer when it does.
 * There is one view per backing array, repointed rather than replaced, so a container decoding a
 * thousand-element array allocates neither a view nor a {@code String} per element. The view is
 * valid only until the next read call; a leaf codec that needs to keep the text calls
 * {@code toString()} before returning.</p>
 *
 * <p>On the wire side this cursor is lenient on decode: inside double quotes it
 * accepts both {@code \x} backslash escapes and {@code ""} doubled quotes, which
 * covers {@code array_out}, {@code record_out} and {@code range_out}. It is lenient
 * about segments too — {@code array_in} accepts only one segment per element and
 * rejects {@code {ab"cd"}}, where this cursor concatenates as {@code record_in} does.
 * Leniency only widens what decodes; it never changes the value of a literal the
 * server itself accepts. The per-container escaping differences only matter on
 * encode, which the leaf writers already own.</p>
 */
final class LiteralCursor {

  private static final char[] EMPTY = new char[0];

  private final char[] src;
  private final int start;
  private final int end;
  private int pos;

  private char[] scratch = EMPTY;

  private char[] tokenBuf;
  private int tokenOff;
  private int tokenLen;
  private boolean tokenQuoted;

  // Findings of the structural pass over the current element, consumed by the value pass.
  private boolean probeHasQuote;
  private boolean probeEscapes;
  private int probeSegments;
  private int probeSegmentEnd;

  // One view per backing array, repointed at each token by moving position/limit. The source array
  // never changes, so its view is built once; the scratch view is rebuilt only when the buffer it
  // wraps is replaced by a larger one.
  private final CharBuffer sourceView;
  private CharBuffer scratchView = CharBuffer.wrap(EMPTY);

  private LiteralCursor(char[] src, int offset, int length) {
    this.src = src;
    this.start = offset;
    this.pos = offset;
    this.end = offset + length;
    this.tokenBuf = src;
    this.sourceView = CharBuffer.wrap(src);
  }

  /**
   * Opens a cursor over {@code literal}, reading it in place whenever the backing {@code char[]} is
   * reachable. A {@link CharBuffer} that exposes its array qualifies, which covers the nesting case
   * for free: {@link #getToken()} hands out such a buffer, so an array of composites or a range
   * inside a composite reaches its child codec with no copy. Anything else — a {@code String}, a
   * read-only buffer, a foreign sequence — is copied out once per literal, never per token.
   *
   * <p>A child cursor built over a parent's token borrows the parent's buffer for the length of the
   * child's decode, which is nested inside the call that produced the token. The parent cannot
   * advance until that call returns, so the characters cannot move under the child.</p>
   */
  static LiteralCursor over(CharSequence literal) {
    if (literal instanceof CharBuffer) {
      CharBuffer buf = (CharBuffer) literal;
      if (buf.hasArray()) {
        return new LiteralCursor(buf.array(), buf.arrayOffset() + buf.position(), buf.remaining());
      }
    }
    char[] chars = literal.toString().toCharArray();
    return new LiteralCursor(chars, 0, chars.length);
  }

  /**
   * A second cursor over the same characters, positioned at the start. The array decoder makes a
   * cheap structural pass to size the result before a second pass fills it; both read the same
   * backing buffer, so the literal is never copied twice.
   */
  LiteralCursor restart() {
    return new LiteralCursor(src, start, end - start);
  }

  // -------------------------- structural primitives --------------------------

  boolean atEnd() {
    return pos >= end;
  }

  char peek() {
    return pos < end ? src[pos] : '\0';
  }

  void skipWhitespace() {
    while (pos < end && isWhitespace(src[pos])) {
      pos++;
    }
  }

  /** Consumes {@code c} (after leading whitespace), or fails for a malformed literal. */
  void expect(char c) throws SQLException {
    skipWhitespace();
    if (pos >= end || src[pos] != c) {
      throw malformed(c);
    }
    pos++;
  }

  /** Consumes {@code c} if present (after leading whitespace) and reports whether it did. */
  boolean tryConsume(char c) {
    skipWhitespace();
    if (pos < end && src[pos] == c) {
      pos++;
      return true;
    }
    return false;
  }

  /**
   * Consumes {@code keyword} (case-insensitive, after leading whitespace) when it
   * appears as a complete token — followed by end-of-input, whitespace, or a container
   * delimiter ({@code ','}, {@code '}'}, {@code ')'}, {@code ']'}) — and reports whether
   * it did. Used for the range {@code empty} literal, including when it sits inside a
   * multirange ({@code {empty}}, {@code {[1,2),empty}}), where it is followed by
   * {@code ','} or {@code '}'} rather than whitespace.
   */
  boolean consumeKeyword(String keyword) {
    skipWhitespace();
    int n = keyword.length();
    if (pos + n > end) {
      return false;
    }
    for (int i = 0; i < n; i++) {
      if (Character.toLowerCase(src[pos + i]) != Character.toLowerCase(keyword.charAt(i))) {
        return false;
      }
    }
    if (pos + n < end) {
      char next = src[pos + n];
      if (!isWhitespace(next) && next != ',' && next != '}' && next != ')' && next != ']') {
        return false;
      }
    }
    pos += n;
    skipWhitespace();
    return true;
  }

  /** The full backing literal as a string, for diagnostics on malformed input. */
  String literal() {
    return new String(src, start, end - start);
  }

  /**
   * Fails unless the literal ends where the container did, trailing whitespace aside. The three
   * input functions all end with this check ({@code record_in}'s "Junk after right parenthesis"),
   * and without it text after the closing bracket is silently dropped, so {@code (a,1)x} would
   * decode as {@code (a,1)}.
   *
   * <p>For the container that owns the whole literal only. A nested container is handed exactly its
   * own slice, and a multirange peels several ranges off one cursor, so neither can call this.</p>
   */
  void expectEnd() throws SQLException {
    skipWhitespace();
    if (pos < end) {
      throw Exceptions.junkAfterLiteral(pos, literal());
    }
  }

  /**
   * Skips the leading {@code [l:u]=} dimension prefix of an array literal, if any.
   * The bounds are discarded, matching the existing JDBC array behaviour.
   */
  void skipDimensionPrefix() {
    skipWhitespace();
    if (pos < end && src[pos] == '[') {
      while (pos < end && src[pos] != '=') {
        pos++;
      }
      if (pos < end) {
        pos++; // consume '='
      }
    }
  }

  /**
   * Counts the consecutive opening braces at the current position without
   * consuming them — the dimensionality of the array literal.
   */
  int countLeadingBraces() {
    int p = pos;
    int dims = 0;
    while (p < end && isWhitespace(src[p])) {
      p++;
    }
    while (p < end && src[p] == '{') {
      dims++;
      p++;
      while (p < end && isWhitespace(src[p])) {
        p++;
      }
    }
    return dims;
  }

  // -------------------------- value reading --------------------------

  /**
   * Reads one array element up to (but not consuming) the next {@code delim} or the
   * closing {@code '}'}, with the whitespace rule of {@code array_in}: whitespace
   * outside quotes is not part of the value, whitespace inside quotes is. The decoded
   * value is exposed via {@link #getToken()} / {@link #tokenLength()} and
   * {@link #tokenWasQuoted()}.
   *
   * <p>Only {@code '}'} terminates an unquoted run, not every bracket kind: an unquoted
   * array element may be a literal {@code )} or {@code ]}. The server quotes any value
   * containing the container's own delimiter or close bracket, so those only ever appear
   * unquoted as structural tokens.</p>
   *
   * <p>Only ASCII whitespace is stripped. A Unicode space such as U+2001 is part of the
   * value, as it is for the server.</p>
   *
   * @param delim the element delimiter (for example {@code ','} or {@code ';'})
   */
  void readArrayElement(char delim) throws SQLException {
    scanValue(delim, '}', '}', Whitespace.STRIP);
  }

  /**
   * Reads one composite field or range bound up to (but not consuming) the next
   * {@code delim} or {@code close}, with the whitespace rule of {@code record_in} and
   * {@code range_parse_bound}: whitespace belongs to the value wherever it appears, so
   * {@code ( x,y)} has a first field of {@code " x"}. Those parsers strip whitespace only
   * around the literal as a whole, which the container driver does through
   * {@link #skipWhitespace()} and {@link #expect(char)}.
   *
   * <p>An unquoted run is terminated by {@code delim} or {@code close} alone, so an
   * unquoted composite field may hold {@code {}}/{@code []} — an empty array field, for
   * instance.</p>
   *
   * @param delim the element delimiter
   * @param close the container's closing bracket
   */
  void readVerbatim(char delim, char close) throws SQLException {
    scanValue(delim, close, close, Whitespace.KEEP);
  }

  /**
   * Reads one value the way {@link #readVerbatim(char, char)} does, but accepting either of
   * two closing brackets: a range upper bound may be followed by {@code ']'} (inclusive) or
   * {@code ')'} (exclusive).
   *
   * @param delim the element delimiter
   * @param close1 one acceptable closing bracket
   * @param close2 the other acceptable closing bracket
   */
  void readVerbatim(char delim, char close1, char close2) throws SQLException {
    scanValue(delim, close1, close2, Whitespace.KEEP);
  }

  /** Whether whitespace outside quotes belongs to the value. */
  private enum Whitespace {
    /** {@code array_in}: whitespace around the value is structural, not data. */
    STRIP,
    /** {@code record_in} / {@code range_parse_bound}: whitespace is data. */
    KEEP
  }

  /**
   * Reads one element the way the server's own parsers do: a value is a run of segments,
   * quoted and unquoted, concatenated into one string. {@code ab"cd"} is therefore
   * {@code abcd} and {@code ( "x",y)} has a first field of {@code " x"}, and a delimiter
   * only terminates the element when it appears outside quotes.
   */
  private void scanValue(char delim, char close1, char close2, Whitespace ws) throws SQLException {
    if (ws == Whitespace.STRIP) {
      skipWhitespace();
    }
    int runStart = pos;
    int runEnd = probe(runStart, delim, close1, close2);
    pos = runEnd;
    tokenQuoted = probeHasQuote;

    // Where the value ends once whitespace outside quotes is discarded. The closing quote
    // of a quoted segment is not whitespace, so this never eats into a quoted segment.
    int valueEnd = runEnd;
    if (ws == Whitespace.STRIP) {
      while (valueEnd > runStart && isWhitespace(src[valueEnd - 1])) {
        valueEnd--;
      }
    }

    if (!probeHasQuote && !probeEscapes) {
      // The common case: the run is the value, so it stays a view into src. A backslash
      // counts as an escape outside quotes too, so it does not reach here.
      tokenBuf = src;
      tokenOff = runStart;
      tokenLen = valueEnd - runStart;
      return;
    }
    if (!probeEscapes && probeSegments == 1
        && src[runStart] == '"' && probeSegmentEnd == valueEnd - 1) {
      // One fully quoted segment with nothing to unescape — what the server itself emits.
      // The value is the interior, still a view into src.
      tokenBuf = src;
      tokenOff = runStart + 1;
      tokenLen = probeSegmentEnd - runStart - 1;
      return;
    }
    unescapeInto(runStart, runEnd, ws);
  }

  /**
   * Copies {@code [from, to)} into the scratch buffer, dropping the quotes and resolving
   * the {@code \x} and {@code ""} escapes, and points the token at the result.
   */
  private void unescapeInto(int from, int to, Whitespace ws) {
    char[] buf = ensureScratch(to - from);
    int n = 0;
    int lastKept = 0; // end of the value once trailing unquoted whitespace is dropped
    boolean inQuotes = false;
    int q = from;
    while (q < to) {
      char c = src[q];
      if (c == '\\') {
        buf[n++] = src[q + 1];
        lastKept = n;
        q += 2;
        continue;
      }
      if (c == '"') {
        if (inQuotes && q + 1 < to && src[q + 1] == '"') {
          buf[n++] = '"';
          lastKept = n;
          q += 2;
          continue;
        }
        inQuotes = !inQuotes;
        q++;
        continue;
      }
      buf[n++] = c;
      q++;
      if (inQuotes || !isWhitespace(c)) {
        lastKept = n;
      }
    }
    tokenBuf = buf;
    tokenOff = 0;
    tokenLen = ws == Whitespace.STRIP ? lastKept : n;
  }

  /**
   * Scans one element without materializing it and returns the index of its terminator,
   * recording in the {@code probe*} fields what the value pass then needs: whether the run
   * was quoted at all, whether it holds escapes, and where its single quoted segment ends
   * when that is all there is.
   */
  private int probe(int from, char delim, char close1, char close2) throws SQLException {
    probeHasQuote = false;
    probeEscapes = false;
    probeSegments = 0;
    probeSegmentEnd = -1;
    boolean inQuotes = false;
    int p = from;
    while (p < end) {
      char c = src[p];
      if (c == '\\') {
        if (p + 1 >= end) {
          throw malformed(close1); // the literal ends inside an escape
        }
        probeEscapes = true;
        p += 2;
        continue;
      }
      if (c == '"') {
        probeHasQuote = true;
        if (!inQuotes) {
          inQuotes = true;
          probeSegments++;
          p++;
        } else if (p + 1 < end && src[p + 1] == '"') {
          probeEscapes = true;
          p += 2;
        } else {
          inQuotes = false;
          probeSegmentEnd = p;
          p++;
        }
        continue;
      }
      if (!inQuotes && (c == delim || c == close1 || c == close2)) {
        break;
      }
      p++;
    }
    if (inQuotes) {
      throw malformed('"');
    }
    return p;
  }

  /**
   * The current token as a borrowed {@link CharSequence}. One of two view instances is returned,
   * repointed at each read, so nothing is allocated per token; the caller must finish
   * reading — or {@code toString()} — before advancing the cursor.
   */
  CharSequence getToken() {
    CharBuffer view = tokenBuf == src ? sourceView : scratchView;
    view.clear();
    view.position(tokenOff);
    view.limit(tokenOff + tokenLen);
    return view;
  }

  int tokenLength() {
    return tokenLen;
  }

  boolean tokenWasQuoted() {
    return tokenQuoted;
  }

  /** Reports whether the current token equals {@code s} (case-sensitive). */
  boolean tokenEquals(String s) {
    if (tokenLen != s.length()) {
      return false;
    }
    for (int i = 0; i < tokenLen; i++) {
      if (tokenBuf[tokenOff + i] != s.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  // -------------------------- structural skipping (measure pass) --------------------------

  /**
   * Skips one scalar element without materializing its value. Runs the same structural
   * scan as {@link #readArrayElement}, so the measuring pass and the value pass agree on
   * where every element ends — including for a delimiter that sits inside quotes.
   */
  void skipScalar(char delim, char close) throws SQLException {
    skipWhitespace();
    pos = probe(pos, delim, close, close);
  }

  /**
   * Captures the raw {@code {...}} sub-array literal at the cursor (leading whitespace skipped) and
   * advances past it, so one row of a multi-dimensional array can be exposed as a nested literal.
   */
  String captureSubarray() throws SQLException {
    skipWhitespace();
    int subStart = pos;
    skipSubarray();
    return new String(src, subStart, pos - subStart);
  }

  /** Skips one balanced {@code {...}} sub-array (quote-aware). */
  void skipSubarray() throws SQLException {
    expect('{');
    int depth = 1;
    while (depth > 0) {
      if (pos >= end) {
        throw malformed('}');
      }
      char c = src[pos++];
      if (c == '\\') {
        pos++; // an escaped brace is data, not structure
      } else if (c == '"') {
        skipRestOfQuoted();
      } else if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
      }
    }
  }

  // -------------------------- internals --------------------------

  /** Assumes the opening quote was already consumed; advances past the closing quote. */
  private void skipRestOfQuoted() {
    while (pos < end) {
      char c = src[pos++];
      if (c == '\\') {
        if (pos < end) {
          pos++;
        }
      } else if (c == '"') {
        if (pos < end && src[pos] == '"') {
          pos++; // doubled quote, keep scanning
        } else {
          return; // closing quote
        }
      }
    }
  }

  private char[] ensureScratch(int needed) {
    if (scratch.length < needed) {
      scratch = new char[Math.max(needed, 16)];
      scratchView = CharBuffer.wrap(scratch);
    }
    return scratch;
  }

  private static boolean isWhitespace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B;
  }

  private SQLException malformed(char expected) {
    return Exceptions.malformedLiteral(expected, pos);
  }
}
