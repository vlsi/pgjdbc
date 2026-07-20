/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import org.postgresql.core.ServerVersion;
import org.postgresql.core.v3.ConnectionFactoryImpl.InitialSessionParameters;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

/**
 * Fails when a change to {@link InitialSessionParameters} moves {@code extra_float_digits} or
 * {@code application_name} to a different channel. Each row of {@link #matrix()} fixes one assumed
 * version, server version, and {@code application_name}, and states both results: what the startup
 * packet carries, and the exact post-authentication {@code SET}.
 *
 * <p>The rows are chosen rather than exhaustive. They cover each version boundary the decision
 * turns on (8.x, 9.0, and 12) from the assumed side and from the real side. Add a row when a change
 * introduces a boundary; editing an expected value is how a change to what the driver puts on the
 * wire becomes visible in review.</p>
 *
 * <p>A failure prints the row label and whether the startup packet or the initial query
 * disagreed.</p>
 */
class InitialSessionParametersTest {

  static Stream<Arguments> matrix() {
    int pg83 = ServerVersion.v8_3.getVersionNum();
    int pg84 = ServerVersion.v8_4.getVersionNum();
    int pg90 = ServerVersion.v9_0.getVersionNum();
    int pg94 = ServerVersion.v9_4.getVersionNum();
    int pg11 = ServerVersion.v11.getVersionNum();
    int pg12 = ServerVersion.v12.getVersionNum();
    int pg14 = ServerVersion.v14.getVersionNum();

    return Stream.of(
        // --- no application_name: extra_float_digits is decided from the real server version alone
        arguments("no-app, server 8.4", null, pg84, null, "[]",
            "SET extra_float_digits = 2"),
        arguments("no-app, server 9.0", null, pg90, null, "[]",
            "SET extra_float_digits = 3"),
        arguments("no-app, server 11", null, pg11, null, "[]",
            "SET extra_float_digits = 3"),
        arguments("no-app, server 12", null, pg12, null, "[]", ""),
        arguments("no-app, server 14", null, pg14, null, "[]", ""),

        // Discussion #4306: with assumeMinServerVersion at least 9.0 and below 12, the driver
        // knows before it connects that extra_float_digits belongs in the startup packet, and
        // sends it there rather than in a SET that a restricted session, such as Greenplum
        // retrieve mode, cannot run.
        arguments("#4306: assume 9.3, server 9.4", "9.3", pg94, null,
            "[extra_float_digits=3]", ""),

        // --- application_name delivered in the startup packet (assumed version >= 9.0);
        //     extra_float_digits joins it there when the assumed version is also below 12
        arguments("app in packet, server 14", "9.0", pg14, "myapp",
            "[extra_float_digits=3, application_name=myapp]", ""),
        arguments("app in packet, server 11", "9.0", pg11, "myapp",
            "[extra_float_digits=3, application_name=myapp]", ""),
        arguments("app in packet, assume 14, server 14", "14", pg14, "myapp",
            "[application_name=myapp]", ""),
        // The assumed version can overstate the real one, and the packet is built before the real
        // one is known. An 8.x server accepts no more than 2, so it refuses this packet. 42.7.3
        // built the same packet: any assumeMinServerVersion >= 9.0 pinned extra_float_digits=3
        // there.
        arguments("app in packet, assume 9.4, server 8.4", "9.4", pg84, "myapp",
            "[extra_float_digits=3, application_name=myapp]", ""),

        // --- application_name delivered via SET (assumed version < 9.0 but real server supports it)
        arguments("app via set, server 14", null, pg14, "myapp", "[]",
            "SET application_name = 'myapp'"),
        arguments("app via set + efd, server 11", null, pg11, "myapp", "[]",
            "SET extra_float_digits = 3;SET application_name = 'myapp'"),
        arguments("app via set + efd, assume 8.4, server 9.0", "8.4", pg90, "myapp", "[]",
            "SET extra_float_digits = 3;SET application_name = 'myapp'"),
        // real server too old for application_name: only extra_float_digits is sent
        arguments("app dropped, server 8.4", null, pg84, "myapp", "[]",
            "SET extra_float_digits = 2"),
        arguments("app dropped, assume 8.4, server 8.3", "8.4", pg83, "myapp", "[]",
            "SET extra_float_digits = 2")
    );
  }

  /**
   * Compares the startup parameters through {@code List.toString()}, so a row pins the order they
   * are added in as well as their content.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("matrix")
  void placement(String name, String assumeMinServerVersion, int serverVersionNum,
      String applicationName, String expectedStartupPacket, String expectedInitialQuery)
      throws Exception {
    InitialSessionParameters params =
        InitialSessionParameters.of(ServerVersion.from(assumeMinServerVersion), applicationName);

    assertEquals(expectedStartupPacket, params.startupPacketParameters().toString(),
        name + " [startup packet]");
    assertEquals(expectedInitialQuery,
        params.initialQuerySql(serverVersionNum, true),
        name + " [initial query]");
  }

  @ParameterizedTest(name = "scs={0}")
  @MethodSource("escapingCases")
  void applicationNameIsEscaped(boolean standardConformingStrings, String expectedInitialQuery)
      throws Exception {
    // An unset assumed version keeps application_name out of the startup packet, so it goes into
    // the SET, the only path that escapes it: the packet carries a value verbatim. The value here
    // has a single quote, doubled either way, and a backslash, doubled only when
    // standard_conforming_strings is off.
    InitialSessionParameters params =
        InitialSessionParameters.of(ServerVersion.from(null), "a'\\b");

    assertEquals(expectedInitialQuery,
        params.initialQuerySql(ServerVersion.v14.getVersionNum(), standardConformingStrings));
  }

  static Stream<Arguments> escapingCases() {
    return Stream.of(
        arguments(true, "SET application_name = 'a''\\b'"),
        arguments(false, "SET application_name = 'a''\\\\b'"));
  }
}
