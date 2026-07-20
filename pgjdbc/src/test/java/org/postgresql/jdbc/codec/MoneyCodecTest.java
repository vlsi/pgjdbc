/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.TypeName;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * The {@code money} text parse across locale renderings.
 *
 * <p>{@code money} is the one type the driver only ever reads in text: the value on the wire is the
 * server's {@code lc_monetary} rendering, and the properties that shape it &mdash; the currency
 * symbol, the grouping and decimal characters, the fraction digits &mdash; do not travel with it.
 * The parser therefore reads the literal's shape rather than a known format, which makes the
 * renderings below the actual contract.</p>
 *
 * <p>Each literal is the shape glibc's own locale data produces, read off
 * {@code locale -k LC_MONETARY} rather than written from memory: {@code ja_JP} has
 * {@code frac_digits=0} with a comma grouping, {@code de_DE} and {@code tr_TR} swap the two
 * characters against {@code en_US}, {@code fr_FR} and {@code ru_RU} group with a space, and
 * {@code ar_KW} has {@code frac_digits=3}. {@code uk_UA} and {@code ar_SA} are the ones that put a
 * currency symbol containing a {@code '.'} after the amount &mdash; of the 32 glibc locales whose
 * symbol carries a separator character, those two are the only ones with {@code n_cs_precedes=0}, and
 * the trailing dot of {@code грн.} used to be read as the decimal point. Every glibc locale that sets
 * {@code negative_sign} sets it to ASCII {@code -}, so the sign needs no non-ASCII handling.</p>
 *
 * <p>{@code MoneyLocaleTest} checks the same parse against a server actually running each locale;
 * this one needs no server, so it runs everywhere.</p>
 */
class MoneyCodecTest {

  private static final PgType MONEY = new PgType(
      TypeName.of("pg_catalog", "money"), "money", Oid.MONEY, 'b', 'N', -1, 0, 0, 0);

  private static BigDecimal parse(String literal) throws SQLException {
    return MoneyCodec.INSTANCE.decodeAsBigDecimal(literal, MONEY, null);
  }

  @ParameterizedTest(name = "{1}: {0}")
  @CsvSource(delimiter = '|', value = {
      // literal            | locale (frac_digits, separators)        | expected
      "  $1,234.56          | en_US 2, group ',' decimal '.'          | 1234.56",
      "  -$1,234.56         | en_US negative                          | -1234.56",
      "  ($1,234.56)        | en_US parenthesised negative            | -1234.56",
      "  $0.01              | en_US one cent                          | 0.01",
      "  1.234,56 €         | de_DE 2, group '.' decimal ','          | 1234.56",
      "  1.234,56 ₺         | tr_TR 2, group '.' decimal ','          | 1234.56",
      "  1 234,56 €         | fr_FR 2, group ' ' decimal ','          | 1234.56",
      "  1 234,56 ₽         | ru_RU 2, group ' ' decimal ','          | 1234.56",
      "  1 234 567,89 ₽     | ru_RU repeated space grouping           | 1234567.89",
      "  ￥1,234             | ja_JP 0, group ','                      | 1234",
      "  ￥1,234,567         | ja_JP 0, repeated grouping              | 1234567",
      "  -￥1,234            | ja_JP 0, negative                       | -1234",
      "  ￥123               | ja_JP 0, ungrouped                      | 123",
      "  د.ك. 1,234.567     | ar_KW 3, both separators present        | 1234.567",
      "  1 234,56грн.       | uk_UA 2, symbol ends in a dot           | 1234.56",
      "  1,234.56 ر.س       | ar_SA 2, symbol carries a dot           | 1234.56",
      "  92233720368547758.07 | int64 maximum                         | 92233720368547758.07",
  })
  void parsesLocaleRendering(String literal, String locale, String expected) throws SQLException {
    assertEquals(new BigDecimal(expected), parse(literal.trim()),
        () -> "money literal '" + literal.trim() + "' from " + locale);
  }

  @Test
  void groupedThousandsAreNotAFraction() throws SQLException {
    // The regression this pins: the parser took the last '.' or ',' as the decimal separator, so a
    // locale that prints no fraction at all (frac_digits=0, as ja_JP does) had its thousands
    // separator read as a decimal point and every value came back 1000x too small.
    assertEquals(new BigDecimal("1234"), parse("1,234"));
    assertEquals(new BigDecimal("1234567"), parse("1,234,567"));
  }

  @Test
  void fourDigitsBeforeASingleSeparatorMeanAFraction() throws SQLException {
    // A group is exactly three digits, so a wider leading part rules grouping out: 1234,567 can only
    // be a fraction, whatever the locale.
    assertEquals(new BigDecimal("1234.567"), parse("1234,567"));
  }

  // ==================== the shape the literal cannot settle ====================

  // "1.234" is 1234 where the locale prints no fraction and 1.234 where it prints three. The shape
  // says nothing, so the currency symbol decides, through MoneyFractionDigits.

  @Test
  void threeFractionDigitCurrencyReadsTheSeparatorAsAFraction() throws SQLException {
    // ar_KW: 1.234 dinars, which without the symbol would read as 1234 -- a thousandfold miss.
    assertEquals(new BigDecimal("1.234"), parse("د.ك. 1.234"));
  }

  @Test
  void zeroFractionDigitCurrencyReadsTheSeparatorAsGrouping() throws SQLException {
    // ja_JP prints no fraction at all, so the same shape is a thousand yen.
    assertEquals(new BigDecimal("1234"), parse("￥1,234"));
  }

  @Test
  void sharedSymbolIsSafeBecauseTwoDigitLocalesCannotReachHere() throws SQLException {
    // £ belongs to ar_SS (three fraction digits) and to en_GB (two). Only the first can produce this
    // shape -- en_GB would print "£1,234.00" -- so reading it as a fraction is right, and the shared
    // symbol costs nothing.
    assertEquals(new BigDecimal("1.234"), parse("£1.234"));
    assertEquals(new BigDecimal("1234.00"), parse("£1,234.00"));
  }

  @Test
  void unknownCurrencyKeepsTheGroupedReading() throws SQLException {
    // No symbol the table knows, so the reading every remaining locale intends: grouped. This is what
    // a server on a libc other than glibc, or a currency added after the table was generated, gets.
    assertEquals(new BigDecimal("1234"), parse("1.234"));
    assertEquals(new BigDecimal("1234"), parse("XYZ 1.234"));
  }

  @Test
  void refusesLiteralWithoutDigits() {
    assertThrows(PSQLException.class, () -> parse("abc"),
        "a money literal with no digit at all should be refused");
  }
}
