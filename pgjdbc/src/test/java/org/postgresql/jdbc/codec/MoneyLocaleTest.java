/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Server truth for the {@code money} text parse, one {@code lc_monetary} at a time.
 *
 * <p>{@code money} is the only type the driver reads solely in text, because its rendering — the
 * currency symbol, the grouping and decimal characters, the fraction digits — comes from
 * {@code lc_monetary} and none of it travels with the value. Every other test of this parse feeds the
 * codec a literal written by hand, which proves only that the driver agrees with whoever wrote the
 * test. Here the server renders the value and the same server converts it back, so the assertion is
 * against ground truth: {@code money} read through the driver must equal {@code money::numeric} read
 * from the server.</p>
 *
 * <p>Needs a server whose image carries the locales (see {@code docker/postgres-head/Dockerfile}),
 * so it runs only when {@code pgjdbc.test.serverLocales} is set. The property is a promise rather
 * than a request: with it set, a locale the server cannot switch to fails the test instead of
 * skipping it, so dropping a locale from the image is caught rather than quietly ignored. Run it
 * against such a server with {@code -Dpgjdbc.test.serverLocales=true} (any {@code pgjdbc.*} property
 * reaches the test JVM through the shared test-base plugin, as a {@code -D} or a {@code -P}).</p>
 */
@EnabledIfSystemProperty(named = "pgjdbc.test.serverLocales", matches = ".+",
    disabledReason = "needs a server image carrying the extra locales")
class MoneyLocaleTest {

  /**
   * The locales the server image installs, each shaping {@code money} differently: {@code frac_digits}
   * 0 for {@code ja_JP} and 3 for {@code ar_KW} against 2 everywhere else, a dot grouping in
   * {@code de_DE} and {@code tr_TR} against a comma in {@code en_US} and {@code ja_JP}, and a space in
   * {@code fr_FR}, {@code ru_RU}, {@code sv_SE} and {@code nb_NO}. {@code uk_UA} and {@code ar_SA} put
   * a currency symbol that itself contains a {@code '.'} after the amount ({@code 1 234,56грн.}),
   * which is the shape that used to be read as a decimal point.
   */
  private static final List<String> LOCALES = Arrays.asList(
      "C", "en_US.UTF-8", "de_DE.UTF-8", "fr_FR.UTF-8", "ru_RU.UTF-8",
      "sv_SE.UTF-8", "nb_NO.UTF-8", "ja_JP.UTF-8", "ar_KW.UTF-8", "ar_SA.UTF-8",
      "uk_UA.UTF-8", "tr_TR.UTF-8");

  /**
   * Amounts covering the renderings the parse has to tell apart, including the ones below 1000 that
   * collapse to the shape no literal can settle -- in a three-fraction-digit locale 1 renders as
   * "1.000", the shape a no-fraction locale uses for a thousand. Those pass only because the currency
   * symbol resolves it (see {@code MoneyFractionDigits}); before that they read a thousandfold high.
   * The largest amount stops short of money's own int64 limit, which each locale reaches at a
   * different value -- three fraction digits overflow it a thousand times sooner than two.
   */
  private static final List<String> AMOUNTS = Arrays.asList(
      "0", "1", "12", "123", "999", "1000", "1234", "12345", "1234567", "1234.56",
      "-1", "-999", "-1234", "-1234567", "-1234.56", "9223372036854");

  private static Connection con;

  @BeforeAll
  static void setUp() throws Exception {
    con = TestUtil.openDB();
  }

  @AfterAll
  static void tearDown() throws SQLException {
    TestUtil.closeDB(con);
  }

  static List<Arguments> localesAndAmounts() {
    List<Arguments> out = new ArrayList<>();
    for (String locale : LOCALES) {
      for (String amount : AMOUNTS) {
        out.add(Arguments.of(locale, amount));
      }
    }
    return out;
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("localesAndAmounts")
  void moneyMatchesServerNumeric(String locale, String amount) throws Exception {
    setMonetaryLocale(locale);
    // Cast from numeric rather than from a literal, so money_in's own locale parsing stays out of it
    // and the input side cannot mask a read-side bug.
    try (Statement st = con.createStatement();
         ResultSet rs = st.executeQuery(
             "SELECT (" + amount + "::numeric)::money, (" + amount + "::numeric)::money::numeric")) {
      rs.next();
      BigDecimal driver = rs.getBigDecimal(1);
      BigDecimal server = rs.getBigDecimal(2);
      String rendering = rs.getString(1);
      assertEquals(0, server.compareTo(driver),
          () -> "money read through the driver must equal the server's own money::numeric"
              + " (lc_monetary=" + locale + ", amount=" + amount + ", server rendering '"
              + rendering + "', driver read " + driver + ", server " + server + ")");
    }
  }

  @Test
  void theCurrencySymbolTableStillMatchesTheServer() throws Exception {
    // The drift alarm for MoneyFractionDigits, which is generated from glibc rather than read from
    // the server. ar_KW is the image's three-fraction-digit locale: if a new glibc renamed its
    // symbol, the table would no longer recognise it, the parse would fall back to grouping, and one
    // dinar would read as a thousand. Asserted here rather than trusted to the yearly regeneration.
    setMonetaryLocale("ar_KW.UTF-8");
    try (Statement st = con.createStatement();
         ResultSet rs = st.executeQuery("SELECT (1::numeric)::money, (1::numeric)::money::numeric")) {
      rs.next();
      assertEquals("د.ك. 1.000", rs.getString(1).trim(),
          "the symbol the table was generated for; regenerate it with"
              + " docker/bin/currency-fraction-digits if the server renders another");
      assertEquals(0, rs.getBigDecimal(2).compareTo(rs.getBigDecimal(1)),
          "one dinar, not a thousand");
    }
  }

  /**
   * Switches the session's {@code lc_monetary}. A locale the server does not have fails rather than
   * skips: the property that enables this class states the image carries them.
   */
  private static void setMonetaryLocale(String locale) throws SQLException {
    try (Statement st = con.createStatement()) {
      st.execute("SET lc_monetary = '" + locale + "'");
    } catch (SQLException e) {
      throw new AssertionError("The server cannot switch to lc_monetary='" + locale
          + "'. pgjdbc.test.serverLocales says the image carries the extra locales; see"
          + " docker/postgres-head/Dockerfile.", e);
    }
  }
}
