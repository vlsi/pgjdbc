/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.postgresql.test.TestUtil;
import org.postgresql.test.data.EdgeCase;
import org.postgresql.test.data.IntervalEdgeCases;
import org.postgresql.util.PGInterval;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-truth for {@link PGInterval}'s literal parser: for every catalogued literal the driver and
 * {@code interval_in} must reach the same verdict, and where both accept, the same value.
 *
 * <p>{@code PGInterval} parses interval text itself rather than deferring to a codec, so the offline
 * {@code MalformedLiteralRefusalTest} can only check the literals someone thought to catalogue against
 * the answer someone thought was right. This asks the server instead, which is what makes the
 * catalogue's {@code MALFORMED} entries honest: an entry the server actually accepts fails here rather
 * than quietly pinning the driver to a refusal PostgreSQL does not make.
 *
 * <p>The comparison is on the normalized value, read back as {@code interval::text}, because the server
 * normalizes what it parses ({@code '123'::interval} is {@code 00:02:03}) and the literal's own spelling
 * is not the thing under test.
 */
class PGIntervalServerTruthTest {

  /**
   * Cases where the driver knowingly disagrees with {@code interval_in}, mapped to the reason. Each is
   * asserted to still diverge, so fixing one fails here and prompts removing the entry rather than
   * leaving a stale exemption behind.
   */
  private static final Map<String, String> KNOWN_DIVERGENCES = knownDivergences();

  private static Map<String, String> knownDivergences() {
    Map<String, String> out = new HashMap<>();
    out.put("large_time",
        "PGInterval holds hours in an int, so it cannot represent the server's widest interval "
            + "(2562047788 hours); widening the field would change the public getHours() signature, "
            + "so it is tracked in https://github.com/pgjdbc/pgjdbc/issues/4301");
    out.put("half_microsecond",
        "a sub-microsecond tie rounds to even on the server (0.0000005 -> 0) and away from zero in "
            + "the driver (-> 0.000001)");
    return out;
  }

  private static Connection con;

  @BeforeAll
  static void setUpClass() throws Exception {
    con = TestUtil.openDB();
  }

  @AfterAll
  static void tearDownClass() throws Exception {
    TestUtil.closeDB(con);
  }

  static List<Arguments> cases() {
    List<Arguments> out = new ArrayList<>();
    for (EdgeCase e : IntervalEdgeCases.ALL) {
      out.add(Arguments.of(e.name(), e.literal()));
    }
    for (EdgeCase e : IntervalEdgeCases.MALFORMED) {
      out.add(Arguments.of(e.name(), e.literal()));
    }
    return out;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void parseVerdictMatchesServer(String caseName, String literal) throws SQLException {
    String serverValue = serverParse(literal);
    // Round-trip the driver's own reading through the server so both sides are compared in the same
    // normalized spelling: the driver prints "1 years 2 mons" where the server prints "1 year 2 mons".
    String driverValue = serverParse(driverParse(literal));

    String divergence = KNOWN_DIVERGENCES.get(caseName);
    if (divergence != null) {
      assertNotEquals(serverValue, driverValue,
          () -> "'" + literal + "' now agrees with the server; drop its KNOWN_DIVERGENCES entry ("
              + divergence + ")");
      return;
    }

    if (serverValue == null) {
      assertNull(driverValue,
          () -> "driver accepted '" + literal + "' as " + driverValue
              + "; the server rejects the literal");
      return;
    }
    assertNotNull(driverValue, () -> "driver refused '" + literal
        + "', which the server reads as " + serverValue);
    assertEquals(serverValue, driverValue, () -> "value read from '" + literal + "'");
  }

  /** {@code null} if {@link PGInterval} refuses {@code literal}. */
  private static @Nullable String driverParse(String literal) {
    try {
      return new PGInterval(literal).getValue();
    } catch (SQLException e) {
      return null;
    }
  }

  /** The server's reading of {@code literal}, or {@code null} if {@code interval_in} rejects it. */
  private static @Nullable String serverParse(@Nullable String literal) throws SQLException {
    if (literal == null) {
      return null;
    }
    try (PreparedStatement ps = con.prepareStatement("select ?::interval::text")) {
      ps.setString(1, literal);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    } catch (SQLException e) {
      // interval_in refused. The connection is in autocommit, so the failed statement leaves nothing
      // to roll back.
      return null;
    }
  }
}
