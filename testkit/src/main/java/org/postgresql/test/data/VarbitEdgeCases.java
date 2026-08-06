/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code varbit} (variable-length bit string) values: the empty string, a single bit, byte-
 * aligned and deliberately non-byte-aligned lengths (the binary wire pads the final byte), and lengths
 * that cross a byte boundary. These stress the bit-string codec's length and padding handling.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}); the literal is the raw bit text.
 */
public final class VarbitEdgeCases {
  /**
   * Literals carrying a character that is not a binary digit, which {@code varbit_in} rejects with
   * {@code 22P02}. The packed binary form has one bit per character and no way to express a bad one, so
   * the driver screens the characters itself rather than leaving them to the server.
   *
   * <p>Deliberately absent from {@link #ALL}, whose literals all cast cleanly. Length is not represented
   * here: it is checked against the column's typmod by the server, not by the codec, so a bit string that
   * is merely too long is not malformed on its own.
   */
  public static final List<EdgeCase> MALFORMED = Collections.unmodifiableList(malformed());

  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private VarbitEdgeCases() {
  }

  private static List<EdgeCase> malformed() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("malformed_letters", "abc"));
    out.add(at("malformed_digit_two", "2"));
    out.add(at("malformed_mixed", "10x1"));
    out.add(at("malformed_embedded_space", "1 0"));
    out.add(at("malformed_sign", "-1"));
    // U+FF11, the fullwidth digit one.
    out.add(at("malformed_non_ascii_one", "１"));
    return out;
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("empty", ""));
    out.add(at("single_one", "1"));
    out.add(at("single_zero", "0"));
    out.add(at("seven_bits", "1010101"));
    out.add(at("byte_aligned", "10101010"));
    out.add(at("nine_bits", "101010101"));
    out.add(at("all_ones_byte", "11111111"));
    out.add(at("sixty_four_bits", repeat("10", 32)));
    out.add(at("hundred_bits", repeat("1", 100)));
    return out;
  }

  private static String repeat(String unit, int times) {
    StringBuilder sb = new StringBuilder(unit.length() * times);
    for (int i = 0; i < times; i++) {
      sb.append(unit);
    }
    return sb.toString();
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
