/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;

class PGIntervalTest {

  // ==================== valid literals still parse (F4 must not break) ====================

  @Test
  void parsesPostgresOutputFormat() throws SQLException {
    PGInterval interval = new PGInterval("1 year 2 mons 3 days 04:05:06");
    assertEquals(1, interval.getYears());
    assertEquals(2, interval.getMonths());
    assertEquals(3, interval.getDays());
    assertEquals(4, interval.getHours());
    assertEquals(5, interval.getMinutes());
    assertEquals(6.0, interval.getSeconds(), 0.0001);
  }

  @Test
  void parsesIso8601Format() throws SQLException {
    PGInterval interval = new PGInterval("P1Y2M3DT4H5M6S");
    assertEquals(1, interval.getYears());
    assertEquals(2, interval.getMonths());
    assertEquals(3, interval.getDays());
    assertEquals(4, interval.getHours());
    assertEquals(5, interval.getMinutes());
    assertEquals(6.0, interval.getSeconds(), 0.0001);
  }

  @Test
  void parsesNegativeTime() throws SQLException {
    PGInterval interval = new PGInterval("-01:02:03");
    assertEquals(-1, interval.getHours());
    assertEquals(-2, interval.getMinutes());
    assertEquals(-3.0, interval.getSeconds(), 0.0001);
  }

  @Test
  void parsesVerboseFormatAgo() throws SQLException {
    PGInterval interval = new PGInterval("@ 1 year ago");
    assertEquals(-1, interval.getYears());
  }

  @Test
  void parsesBareNumberAsSeconds() throws SQLException {
    // '5'::interval is 00:00:05 on the server. The parser used to drop the number and read zero.
    PGInterval interval = new PGInterval("5");
    assertEquals(5.0, interval.getSeconds(), 0.0001);
    assertEquals(0, interval.getYears());
    assertEquals(0, interval.getDays());
  }

  @Test
  void parsesZero() throws SQLException {
    assertEquals(0.0, new PGInterval("0").getSeconds(), 0.0001);
  }

  // ==================== unrecognised text refuses instead of reading as zero ====================

  // The parser paired an odd token with a unit word and dropped whatever did not fit, so any
  // unrecognised text -- a word, a stray number, a whole foreign grammar -- read back as a zero
  // interval rather than refusing. A value the server rejects must not decode to one it can never
  // have sent.

  @Test
  void rejectsUnknownWord() {
    assertRejected("abc");
  }

  @Test
  void rejectsUnknownUnit() {
    assertRejected("1 fortnight");
  }

  @Test
  void rejectsTrailingGarbageAfterValidFields() {
    assertRejected("1 year abc");
  }

  @Test
  void rejectsBareNumberPair() {
    assertRejected("1 2");
  }

  @Test
  void rejectsEmpty() {
    // The server rejects ''::interval; an all-zero interval prints as "00:00:00", never as "".
    assertRejected("");
  }

  @Test
  void rejectsSecondTimeToken() {
    assertRejected("01:00:00 02:00:00");
  }

  @Test
  void rejectsNumberFollowedByTimeToken() {
    // The sql_standard day-time form. PR #4296 teaches the parser to read it; until then it must
    // refuse rather than drop the day and shift every field after it.
    assertRejected("1 04:05:06");
  }

  @Test
  void rejectsTextAfterAgo() {
    assertRejected("@ 1 year ago 2 mons");
  }

  @Test
  void rejectsIso8601WithoutField() {
    assertRejected("P");
  }

  @Test
  void rejectsIso8601StrayDesignator() {
    assertRejected("P1X");
  }

  @Test
  void parsesIso8601TrailingT() throws SQLException {
    // The T only switches M from months to minutes, so it needs no field after it: the server reads
    // "P1DT" as one day. PGIntervalServerTruthTest is what caught this being refused.
    assertEquals(1, new PGInterval("P1DT").getDays());
  }

  @Test
  void parsesIso8601BareT() throws SQLException {
    // "PT" is the zero interval on the server, though a bare "P" is refused.
    assertEquals(0.0, new PGInterval("PT").getSeconds(), 0.0001);
  }

  @Test
  void parsesIso8601RepeatedT() throws SQLException {
    // "PT1HT2M" is 01:02:00: a second T keeps M reading as minutes rather than ending the literal.
    PGInterval interval = new PGInterval("PT1HT2M");
    assertEquals(1, interval.getHours());
    assertEquals(2, interval.getMinutes());
  }

  @Test
  void rejectsIso8601NumberWithoutDesignator() {
    assertRejected("P1Y2");
  }

  // ==================== unit words match whole, not by prefix ====================

  // The parser matched a unit word by its prefix, so it accepted units the server has never had:
  // "1 yearsx" read as a year and "1 monsoon" as a month, though the server rejects both literals.

  @ParameterizedTest
  @ValueSource(strings = {"y", "yr", "yrs", "year", "years"})
  void parsesYearUnits(String unit) throws SQLException {
    assertEquals(1, new PGInterval("1 " + unit).getYears(), unit);
  }

  @ParameterizedTest
  @ValueSource(strings = {"mon", "mons", "month", "months"})
  void parsesMonthUnits(String unit) throws SQLException {
    assertEquals(1, new PGInterval("1 " + unit).getMonths(), unit);
  }

  @ParameterizedTest
  @ValueSource(strings = {"d", "day", "days"})
  void parsesDayUnits(String unit) throws SQLException {
    assertEquals(1, new PGInterval("1 " + unit).getDays(), unit);
  }

  @ParameterizedTest
  @ValueSource(strings = {"h", "hr", "hrs", "hour", "hours"})
  void parsesHourUnits(String unit) throws SQLException {
    assertEquals(1, new PGInterval("1 " + unit).getHours(), unit);
  }

  @ParameterizedTest
  @ValueSource(strings = {"m", "min", "mins", "minute", "minutes"})
  void parsesMinuteUnits(String unit) throws SQLException {
    // A bare "m" is minutes on the server, not months, however it reads.
    assertEquals(1, new PGInterval("1 " + unit).getMinutes(), unit);
  }

  @ParameterizedTest
  @ValueSource(strings = {"s", "sec", "secs", "second", "seconds"})
  void parsesSecondUnits(String unit) throws SQLException {
    assertEquals(1.0, new PGInterval("1 " + unit).getSeconds(), 0.0001, unit);
  }

  @ParameterizedTest
  @ValueSource(strings = {"1 yearsx", "1 monsoon", "1 dayss", "1 hourx", "1 minx", "1 secx",
      "1 yearly", "1 monday", "1 seconded"})
  void rejectsUnitWithSuffix(String literal) {
    assertRejected(literal);
  }

  @ParameterizedTest
  @ValueSource(strings = {"1 mo", "1 mos", "1 ye", "1 se", "1 hou"})
  void rejectsTruncatedUnit(String literal) {
    // The server knows no such abbreviation either.
    assertRejected(literal);
  }

  @ParameterizedTest
  @ValueSource(strings = {"1 week", "1 w", "1 decade", "1 century", "1 millennium", "1 ms", "1 us"})
  void rejectsUnitsWithoutAField(String literal) {
    // Units the server accepts but PGInterval has no field for. Refusing is the honest answer; a
    // silent zero, or folding a week into seven days, would both misreport what was read.
    assertRejected(literal);
  }

  // ==================== a verbose literal names each field at most once ====================

  // The server rejects "1 day 2 days"; the parser overwrote the first value and read 2 days.

  @ParameterizedTest
  @ValueSource(strings = {"1 year 2 years", "1 mon 2 mons", "1 day 2 days", "1 hour 2 hours",
      "1 min 2 mins", "3 secs 4 secs", "1 year 2 mons 1 year"})
  void rejectsRepeatedUnit(String literal) {
    assertRejected(literal);
  }

  @ParameterizedTest
  @ValueSource(strings = {"1 hour 00:00:01", "00:00:01 1 hour", "1 min 00:00:01", "00:00:01 1 sec",
      "00:00:01 5"})
  void rejectsTimeTokenOverlappingSpelledOutField(String literal) {
    // The packed hh:mm:ss token fills hours, minutes and seconds, so it cannot share a literal with
    // any of them spelled out, in either order. A trailing bare number is seconds, so it clashes too.
    assertRejected(literal);
  }

  @Test
  void parsesFieldsInAnyOrder() throws SQLException {
    // Only repeats are refused; the server takes the fields in whatever order they come.
    PGInterval interval = new PGInterval("1 mons 2 year");
    assertEquals(2, interval.getYears());
    assertEquals(1, interval.getMonths());
  }

  // ==================== repeated ISO-8601 fields add up ====================

  // interval_in sums a repeated field: P1Y2Y is three years. The parser overwrote instead, reading
  // two, so a literal the server reads as one value decoded as another.

  @Test
  void sumsRepeatedIso8601Years() throws SQLException {
    assertEquals(3, new PGInterval("P1Y2Y").getYears());
  }

  @Test
  void sumsRepeatedIso8601Months() throws SQLException {
    PGInterval interval = new PGInterval("P1Y1M2M");
    assertEquals(1, interval.getYears());
    assertEquals(3, interval.getMonths());
  }

  @Test
  void sumsRepeatedIso8601TimeFields() throws SQLException {
    PGInterval interval = new PGInterval("P1DT1H2H");
    assertEquals(1, interval.getDays());
    assertEquals(3, interval.getHours());
  }

  @Test
  void sumsRepeatedIso8601Seconds() throws SQLException {
    assertEquals(2.0, new PGInterval("PT1S1S").getSeconds(), 0.0001);
  }

  @Test
  void sumsSignedIso8601Repeats() throws SQLException {
    // P1Y-2Y is 1 + (-2), which the server prints as "-1 years".
    assertEquals(-1, new PGInterval("P1Y-2Y").getYears());
  }

  @Test
  void rejectsIso8601RepeatOutOfIntRange() {
    // The sum has to stay a value the server could have sent, so it must not wrap silently.
    assertRejected("P2147483647Y1Y");
  }

  @Test
  void iso8601ClearsFieldsAbsentFromTheLiteral() throws SQLException {
    // Parsing replaces the whole value: the ISO branch used to set only the fields it saw, leaving
    // the rest of a reused instance behind.
    PGInterval interval = new PGInterval(1, 2, 3, 4, 5, 6.0);
    interval.setValue("P7Y");
    assertEquals(7, interval.getYears());
    assertEquals(0, interval.getMonths());
    assertEquals(0, interval.getDays());
    assertEquals(0, interval.getHours());
    assertEquals(0, interval.getMinutes());
    assertEquals(0.0, interval.getSeconds(), 0.0001);
  }

  // ==================== malformed literals refuse cleanly (F4) ====================

  // A malformed interval literal parses numbers with Integer.parseInt / Double.parseDouble and slices
  // tokens with substring, so it can leak a NumberFormatException or a StringIndexOutOfBoundsException.
  // Both paths must instead refuse with a clean PSQLException carrying the server's state for a bad
  // interval literal (invalid_datetime_format, 22007).

  @Test
  void rejectsNonNumericField() {
    // "bad" tokenises to a value token with no unit; a numeric field such as "xx years" leaks NFE.
    assertRejected("xx years");
  }

  @Test
  void rejectsNonNumericIso8601Field() {
    // The ISO-8601 branch (value starts with 'P') is parsed outside the legacy try/catch before the
    // fix, so a non-numeric field there leaked NumberFormatException.
    assertRejected("P1ZY");
  }

  @Test
  void rejectsNonNumericIso8601TimeField() {
    assertRejected("PT1ZH");
  }

  @Test
  void rejectsMalformedTimeToken() {
    // A truncated time token drives token.substring(endHours + 1, endHours + 3) out of range, a
    // StringIndexOutOfBoundsException before the fix.
    assertRejected("1 day 1:x");
  }

  private static void assertRejected(String literal) {
    PSQLException e = assertThrows(PSQLException.class, () -> new PGInterval(literal),
        () -> "interval literal '" + literal + "' should be rejected");
    assertEquals(PSQLState.BAD_DATETIME_FORMAT.getState(), e.getSQLState(),
        () -> "SQLState for rejected interval literal '" + literal + "'");
  }
}
