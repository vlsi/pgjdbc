/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.postgresql.test.util.PgWire.authenticationCleartextPassword;
import static org.postgresql.test.util.PgWire.concat;
import static org.postgresql.test.util.PgWire.countFrontendMessages;
import static org.postgresql.test.util.PgWire.int4;
import static org.postgresql.test.util.PgWire.message;
import static org.postgresql.test.util.PgWire.successfulStartup;

import org.postgresql.PGProperty;
import org.postgresql.core.ConnectionFactory;
import org.postgresql.core.QueryExecutor;
import org.postgresql.test.util.ScriptedSocketFactory;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

/**
 * Fails when a check {@link ConnectionFactoryImpl} applies before the connection is
 * authenticated lets through a handshake it must reject, or turns away a legal one. The
 * peer is untrusted at that point, so every one of those checks is unconditional.
 *
 * <p>The driver writes its request and then reads the reply, so a canned byte script that
 * ignores what was written drives the whole handshake. No server and no socket are
 * involved; see {@link ScriptedSocketFactory}.</p>
 */
class ConnectionFactoryHardeningTest {

  /** Protocol 3.0, the version the driver asks for. */
  private static final int PROTOCOL_3_0 = 3 << 16;

  private static final HostSpec[] HOSTS = {new HostSpec("localhost", 5432)};

  @AfterEach
  void clearScript() {
    ScriptedSocketFactory.clear();
  }

  private static Properties connectionProperties() {
    Properties info = new Properties();
    PGProperty.USER.set(info, "test");
    PGProperty.PG_DBNAME.set(info, "test");
    PGProperty.PASSWORD.set(info, "secret");
    // Neither negotiation has anything to answer with here, and skipping them keeps the
    // script to the messages under test.
    PGProperty.SSL_MODE.set(info, "disable");
    PGProperty.GSS_ENC_MODE.set(info, "disable");
    // Tells the driver the server is new enough to have taken application_name in the
    // startup packet, so a successful connection runs no setup query.
    PGProperty.ASSUME_MIN_SERVER_VERSION.set(info, "9.0");
    PGProperty.SOCKET_FACTORY.set(info, ScriptedSocketFactory.CLASS_NAME);
    return info;
  }

  private static QueryExecutor connect(byte[] script) throws Exception {
    ScriptedSocketFactory.setScript(script);
    return ConnectionFactory.openConnection(HOSTS, connectionProperties());
  }

  private static PSQLException assertConnectFails(byte[] script) {
    ScriptedSocketFactory.setScript(script);
    return assertThrows(PSQLException.class,
        () -> ConnectionFactory.openConnection(HOSTS, connectionProperties()));
  }

  /**
   * A NegotiateProtocolVersion whose body is exactly the protocol version and the count of
   * unrecognised options, with no option names after it.
   */
  private static byte[] negotiateProtocolVersion(int optionCount) {
    return message('v', int4(PROTOCOL_3_0), int4(optionCount));
  }

  @Test
  void authenticationStopsAfterTheRoundTripCap() {
    // A server that answers every password with another password request. Nothing in the
    // protocol forbids it, and before the cap the driver kept answering forever, burning a
    // core and a socket on a peer that has proved nothing. The script holds more requests
    // than the cap allows, so only the cap can end the exchange: on a truncated script the
    // driver would report a connection failure instead, and the SQLState below would not
    // match.
    byte[] script = new byte[0];
    for (int i = 0; i < 70; i++) {
      script = concat(script, authenticationCleartextPassword());
    }

    PSQLException thrown = assertConnectFails(script);

    assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), thrown.getSQLState());
    assertTrue(thrown.getMessage().contains("64"),
        "the failure should quote the cap it enforces: " + thrown.getMessage());
    assertEquals(64, countFrontendMessages(ScriptedSocketFactory.getSentBytes(), 'p'),
        "the driver should answer exactly as many password requests as the cap allows");
  }

  @Test
  void negotiateProtocolVersionRejectsANegativeOptionCount() {
    // The count is a signed int32 and is used as a loop bound. A negative one skipped the
    // loop and left the envelope half-read, so the next message header was taken from
    // inside this message's body.
    PSQLException thrown = assertConnectFails(negotiateProtocolVersion(-1));

    assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), thrown.getSQLState());
    assertTrue(thrown.getMessage().contains("-1"),
        "the failure should quote the count it rejected: " + thrown.getMessage());
  }

  @Test
  void negotiateProtocolVersionRejectsAnOptionCountBeyondTheEnvelope() {
    // Each unrecognised option is at least a NUL byte, so a message with nothing after the
    // count cannot carry one. Reading it would have run past the envelope and into the next
    // message.
    PSQLException thrown = assertConnectFails(negotiateProtocolVersion(1));

    assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), thrown.getSQLState());
    assertTrue(thrown.getMessage().contains("1"),
        "the failure should quote the count it rejected: " + thrown.getMessage());
  }

  @Test
  void negotiateProtocolVersionWithNoUnknownOptionsIsAccepted() throws Exception {
    // The control the three cases above are measured against: a server that downgrades the
    // protocol and recognises every option connects normally. Without it, a check that
    // rejected every NegotiateProtocolVersion would still pass them.
    QueryExecutor executor = connect(concat(negotiateProtocolVersion(0), successfulStartup()));
    try {
      assertEquals(4711, executor.getBackendPID());
    } finally {
      executor.close();
    }
  }
}
