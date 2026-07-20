/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.EnabledForServerVersionRange;
import org.postgresql.test.util.CountingSocketFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

/**
 * Fails when a pre-v12 server ends up with the wrong {@code extra_float_digits}, or when the
 * startup packet stops saving the round trip the {@code SET} costs (discussion #4306). The
 * placement decision itself is unit-tested in
 * {@code org.postgresql.core.v3.InitialSessionParametersTest}; these tests read the outcome back
 * from a live server.
 *
 * <p>Each CI job picks a random {@code assumeMinServerVersion} and passes it to every test in the
 * run (see {@code .github/workflows/matrix.mjs}), so both tests set the property explicitly rather
 * than inheriting whatever the job chose.</p>
 */
class ExtraFloatDigitsStartupTest {

  /**
   * Whichever channel delivers it, {@code extra_float_digits} must end up at 3 on a pre-v12 server
   * so float text round-trips. {@code assumeMinServerVersion=9.0} puts the value in the startup
   * packet; an empty value parses to version 0, the same as an absent property, and so forces the
   * post-authentication {@code SET} ({@link ValueSource} cannot carry {@code null}). The server
   * default before v12 is 0, so a value of 3 proves the driver set it.
   */
  @ParameterizedTest(name = "assumeMinServerVersion=''{0}''")
  @ValueSource(strings = {"", "9.0"})
  @EnabledForServerVersionRange(lt = "12")
  void extraFloatDigitsIsThreeOnPre12(String assumeMinServerVersion) throws Exception {
    Properties props = new Properties();
    props.setProperty(PGProperty.ASSUME_MIN_SERVER_VERSION.getName(), assumeMinServerVersion);

    try (Connection con = TestUtil.openDB(props);
         Statement st = con.createStatement();
         ResultSet rs = st.executeQuery("SHOW extra_float_digits")) {
      assertTrue(rs.next(), "SHOW extra_float_digits returned no row");
      assertEquals("3", rs.getString(1),
          "extra_float_digits must be 3 on a pre-v12 server (assumeMinServerVersion='"
              + assumeMinServerVersion + "')");
    }
  }

  /**
   * Delivering {@code extra_float_digits} in the startup packet instead of a post-authentication
   * {@code SET} saves one round trip on a pre-v12 server. The two connections differ only in
   * {@code assumeMinServerVersion}, so every other exchange in the handshake is the same and the
   * one statement the packet path does not send accounts for the difference.
   */
  @Test
  @EnabledForServerVersionRange(lt = "12")
  void startupPacketSavesOneRoundTripOnPre12() throws Exception {
    long viaPacket = handshakeRoundTrips("9.0"); // 9.0 <= assumeMinServerVersion < 12: startup packet
    long viaSet = handshakeRoundTrips("");        // no assumed version: post-authentication SET

    assertTrue(viaPacket > 0 && viaSet > 0,
        "CountingSocketFactory observed no round trips (viaPacket=" + viaPacket
            + ", viaSet=" + viaSet + "); the socket-factory wiring is broken");
    assertEquals(viaSet - 1, viaPacket,
        "startup-packet delivery should use exactly one fewer round trip than the SET path"
            + " (viaSet=" + viaSet + ", viaPacket=" + viaPacket + ")");
  }

  private static long handshakeRoundTrips(String assumeMinServerVersion) throws Exception {
    CountingSocketFactory.Counters counters = CountingSocketFactory.register();
    try {
      Properties props = new Properties();
      props.setProperty(PGProperty.SOCKET_FACTORY.getName(), CountingSocketFactory.class.getName());
      props.setProperty(PGProperty.SOCKET_FACTORY_ARG.getName(), counters.key());
      props.setProperty(PGProperty.ASSUME_MIN_SERVER_VERSION.getName(), assumeMinServerVersion);
      // getConnection returns once the handshake has finished, so the counter is stable here.
      try (Connection con = TestUtil.openDB(props)) {
        return counters.roundtrips.get();
      }
    } finally {
      CountingSocketFactory.unregister(counters);
    }
  }
}
