/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code interval} values. PostgreSQL stores an interval as months (int32), days (int32) and
 * microseconds (int64), so this catalogue covers those magnitude limits and, more importantly, the
 * sub-second resolution boundary: intervals with exactly one microsecond, a half-microsecond, and
 * nanosecond precision, to probe how the driver rounds or truncates below the microsecond the server
 * keeps.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}): building the bind value needs the driver's
 * {@code PGInterval}. A literal that overflows the server's interval simply makes both drivers raise the
 * same error, so it stays a compatible cell rather than a false finding.
 */
public final class IntervalEdgeCases {
  /**
   * Literals no {@code interval_in} accepts. The parser used to pair each number with the unit word
   * after it and drop whatever did not fit.
   *
   * <p>Deliberately absent from {@link #ALL}, whose literals all cast cleanly. Two shapes are absent from
   * here as well: the empty string, which {@code IntervalCodec.decodeText} maps to {@code null} before the
   * parser sees it (the shared convention for an empty text value, not an interval decision), and the
   * {@code sql_standard} forms such as {@code 1-2} or {@code 1 04:05:06}, which are refused today but
   * become valid once <a href="https://github.com/pgjdbc/pgjdbc/pull/4296">PR #4296</a> lands.
   */
  public static final List<EdgeCase> MALFORMED = Collections.unmodifiableList(malformed());

  /** Every literal that casts cleanly to {@code interval}, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private IntervalEdgeCases() {
  }

  private static List<EdgeCase> malformed() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("malformed_unknown_word", "abc"));
    out.add(at("malformed_unknown_unit", "1 fortnight"));
    out.add(at("malformed_trailing_garbage", "1 year abc"));
    out.add(at("malformed_bare_number_pair", "1 2"));
    out.add(at("malformed_second_time_token", "01:00:00 02:00:00"));
    out.add(at("malformed_iso_stray_designator", "P1X"));
    out.add(at("malformed_unit_with_suffix", "1 yearsx"));
    out.add(at("malformed_repeated_unit", "1 day 2 days"));
    // A bare number reaches Double.parseDouble, which is ASCII-strict, but every field that reaches
    // Integer.parseInt -- a unit word's number, an ISO designator's, and both halves of the packed
    // hh:mm token -- needs its own case: Integer.parseInt reads every Unicode decimal digit, so each
    // of these decoded to a value interval_in rejects.
    // U+FF11 U+FF12 U+FF13, fullwidth one, two, three.
    out.add(at("malformed_non_ascii_digits", "１２３"));
    out.add(at("malformed_non_ascii_digit_unit", "１ year"));
    out.add(at("malformed_non_ascii_digit_iso_date", "P１Y"));
    out.add(at("malformed_non_ascii_digit_iso_time", "PT１H"));
    out.add(at("malformed_non_ascii_digit_time_hours", "１:00:00"));
    out.add(at("malformed_non_ascii_digit_time_minutes", "00:１２:00"));
    // U+2212, the typographic minus that is not the ASCII hyphen-minus.
    out.add(at("malformed_non_ascii_minus", "−1"));
    out.add(at("malformed_non_ascii_minus_unit", "−1 years"));
    return out;
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("zero", "0"));
    out.add(at("one_microsecond", "00:00:00.000001"));
    out.add(at("half_microsecond", "00:00:00.0000005"));
    out.add(at("nanoseconds", "00:00:00.123456789"));
    out.add(at("one_second_minus_epsilon", "00:00:00.999999"));
    out.add(at("mixed", "1 year 2 mons 3 days 04:05:06.789"));
    out.add(at("negative_mixed", "-1 year -2 mons -3 days -04:05:06.789"));
    out.add(at("large_days", "2147483647 days"));
    out.add(at("large_years", "178000000 years"));
    out.add(at("large_negative_years", "-178000000 years"));
    out.add(at("large_time", "2562047788:00:54.775807"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
