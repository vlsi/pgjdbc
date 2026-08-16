/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.TestLogHandler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Pattern;

/**
 * Fails when a protocol trace outgrows {@code maxLogMessageLength}.
 *
 * <p>A trace logged at {@code FINEST} copies the query text and every bound value into one message,
 * so a large statement used to exhaust the heap through logging alone (issue #995). Every message
 * the driver builds must stay within the configured number of characters, and a message that lost
 * characters must say so. Setting the property to {@code 0} must restore the untruncated
 * traces.</p>
 *
 * <p>The handler is attached to the driver's shared logger, so every connection in the JVM feeds it.
 * The class runs isolated to keep a concurrent test's traces, which carry the default limit, out of
 * the assertions.</p>
 */
@Isolated
class MaxLogMessageLengthTest {
  private static final Pattern PROTOCOL_TRACE = Pattern.compile("^ FE=> ");

  private static final Pattern SIMPLE_QUERY_TRACE = Pattern.compile("^ FE=> SimpleQuery");

  private static final Pattern BIND_TRACE = Pattern.compile("^ FE=> Bind");

  private static final Pattern SERVER_TRACE = Pattern.compile("^ <=BE (Error|Notice)");

  private static final String TRUNCATION_MARKER = "...(truncated)";

  private static final int LIMIT = 512;

  /**
   * Long enough that a trace carrying it in full is unmistakably over the limit.
   */
  private static final String LONG_VALUE = repeat('v', 100_000);

  /**
   * Resolves a record the way a handler does. A record logged with parameters carries the format
   * string in {@code getMessage()}, so measuring that would miss text the handler goes on to
   * substitute.
   */
  private static final Formatter FORMATTER = new SimpleFormatter();

  private TestLogHandler log;
  private Logger driverLogger;
  private Level driverLogLevel;

  @BeforeEach
  void setUp() {
    log = new TestLogHandler();
    driverLogger = Logger.getLogger("org.postgresql");
    driverLogger.addHandler(log);
    driverLogLevel = driverLogger.getLevel();
    driverLogger.setLevel(Level.ALL);
  }

  @AfterEach
  void tearDown() {
    driverLogger.removeHandler(log);
    driverLogger.setLevel(driverLogLevel);
    log = null;
  }

  @Test
  void tracesStayWithinTheLimit() throws SQLException {
    runQueries(LIMIT, PreferQueryMode.EXTENDED);

    List<LogRecord> traces = log.getRecordsMatching(PROTOCOL_TRACE);
    assertFalse(traces.isEmpty(), "the driver logged no protocol trace at FINEST");
    for (LogRecord trace : traces) {
      String message = FORMATTER.formatMessage(trace);
      assertTrue(message.length() <= LIMIT,
          () -> "trace of " + message.length() + " characters exceeds the " + LIMIT
              + " character limit: " + abbreviate(message));
    }
    assertTrue(endsWithMarker(traces),
        "a trace carrying a " + LONG_VALUE.length() + " character value must report that it was "
            + "truncated");
  }

  @Test
  void simpleQueryTracesStayWithinTheLimit() throws SQLException {
    // Simple query mode inlines the bound values into the statement text, so the trace grows
    // through a different path than Bind does.
    runQueries(LIMIT, PreferQueryMode.SIMPLE);

    List<LogRecord> traces = log.getRecordsMatching(SIMPLE_QUERY_TRACE);
    assertFalse(traces.isEmpty(), "the driver logged no simple query trace at FINEST");
    for (LogRecord trace : traces) {
      String message = FORMATTER.formatMessage(trace);
      assertTrue(message.length() <= LIMIT,
          () -> "trace of " + message.length() + " characters exceeds the " + LIMIT
              + " character limit: " + abbreviate(message));
    }
    assertTrue(endsWithMarker(traces),
        "a trace carrying a " + LONG_VALUE.length() + " character value must report that it was "
            + "truncated");
  }

  @Test
  void aLongParameterDoesNotHideTheOnesAfterIt() throws SQLException {
    // The message budget alone would be spent on $1, so $2 and $3 would never be reached.
    Properties props = new Properties();
    PGProperty.MAX_LOG_MESSAGE_LENGTH.set(props, LIMIT);
    PGProperty.MAX_LOG_PARAMETER_LENGTH.set(props, 64);
    runQueries(props);

    List<LogRecord> binds = log.getRecordsMatching(BIND_TRACE);
    assertTrue(binds.stream().anyMatch(r -> FORMATTER.formatMessage(r).contains("$3=<('42'::int4)>")),
        () -> "the trace should still reach the last parameter: " + binds);
  }

  @Test
  void serverTextStaysWithinTheLimit() throws SQLException {
    // The server sends back whatever text it was asked to raise, so a receive-side trace grows the
    // same way an outgoing one does.
    Properties props = new Properties();
    PGProperty.MAX_LOG_MESSAGE_LENGTH.set(props, LIMIT);
    try (Connection con = TestUtil.openDB(props)) {
      raise(con, "NOTICE");
      assertThrows(SQLException.class, () -> raise(con, "EXCEPTION"),
          "RAISE EXCEPTION should reach the caller as a SQLException");
    }

    List<LogRecord> traces = log.getRecordsMatching(SERVER_TRACE);
    assertEquals(2, traces.size(), () -> "expected one notice and one error trace, got " + traces);
    for (LogRecord trace : traces) {
      String message = FORMATTER.formatMessage(trace);
      assertTrue(message.length() <= LIMIT,
          () -> "trace of " + message.length() + " characters exceeds the " + LIMIT
              + " character limit: " + abbreviate(message));
    }
  }

  @Test
  void zeroLimitRestoresUntruncatedTraces() throws SQLException {
    runQueries(0, PreferQueryMode.EXTENDED);

    List<LogRecord> traces = log.getRecordsMatching(PROTOCOL_TRACE);
    assertFalse(endsWithMarker(traces), "no trace should be truncated when the limit is 0");
    assertTrue(traces.stream().anyMatch(r -> FORMATTER.formatMessage(r).length() > LONG_VALUE.length()),
        "a trace should carry the bound value in full");
  }

  private static void runQueries(int maxLogMessageLength, PreferQueryMode queryMode)
      throws SQLException {
    Properties props = new Properties();
    PGProperty.MAX_LOG_MESSAGE_LENGTH.set(props, maxLogMessageLength);
    PGProperty.MAX_LOG_PARAMETER_LENGTH.set(props, 0);
    PGProperty.PREFER_QUERY_MODE.set(props, queryMode.value());
    runQueries(props);
  }

  private static void runQueries(Properties props) throws SQLException {
    try (Connection con = TestUtil.openDB(props)) {
      try (PreparedStatement ps = con.prepareStatement("select ?::text, ?::bytea, ?::int4")) {
        ps.setString(1, LONG_VALUE);
        ps.setBytes(2, new byte[100_000]);
        ps.setInt(3, 42);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next(), "select should return a row");
        }
      }
      // A simple query carries the values inside the statement text rather than as binds.
      try (PreparedStatement ps = con.prepareStatement("select '" + LONG_VALUE + "'::text")) {
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next(), "select should return a row");
        }
      }
    }
  }

  private static void raise(Connection con, String level) throws SQLException {
    try (Statement st = con.createStatement()) {
      st.execute("do $$ begin raise " + level + " '%', repeat('x', 100000); end $$");
    }
  }

  private static boolean endsWithMarker(List<LogRecord> traces) {
    return traces.stream().anyMatch(r -> FORMATTER.formatMessage(r).endsWith(TRUNCATION_MARKER));
  }

  private static String abbreviate(String message) {
    return message.length() <= 200 ? message : message.substring(0, 200) + "...";
  }

  private static String repeat(char c, int count) {
    char[] chars = new char[count];
    Arrays.fill(chars, c);
    return new String(chars);
  }
}
