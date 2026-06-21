/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.TestUtil;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Proof of concept for resolving <b>overloaded</b> routines with by-name {@code CallableStatement}
 * parameters.
 *
 * <p>The shipped by-name path (see #4217) resolves each name to a positional index eagerly via a
 * {@code regproc} cast, so it cannot bind an overloaded routine: a bare name is ambiguous and the
 * cast raises. This PoC takes the alternative discussed on that PR — it defers query construction
 * to {@code execute()} and emits PostgreSQL <i>named-argument notation</i>
 * ({@code f(name => $n)}), letting the server perform overload resolution from the actual bind
 * types. OUT/INOUT results are then read back by their result-column names.</p>
 *
 * <p>This is intentionally minimal and standalone (it drives a plain {@link PreparedStatement}
 * rather than integrating into {@code PgCallableStatement}): it only validates the mechanism for
 * the {@code { call f(...) }} form over a <i>function</i> with IN/INOUT arguments. The
 * {@code { ? = call f(...) }} scalar-return form and procedures invoked through {@code CALL} are
 * out of scope here.</p>
 */
class NamedNotationCallProtoTest {

  private Connection con;

  @BeforeEach
  void setUp() throws Exception {
    con = TestUtil.openDB();
    try (Statement stmt = con.createStatement()) {
      // Two overloads that share the same parameter name and differ only by type. Resolution by
      // name (the regproc / name-set approach) cannot tell them apart; the server can, from the
      // bind type.
      stmt.execute("CREATE OR REPLACE FUNCTION ovl(a int)  RETURNS text "
          + "AS $$ SELECT 'int:'  || a $$ LANGUAGE sql");
      stmt.execute("CREATE OR REPLACE FUNCTION ovl(a text) RETURNS text "
          + "AS $$ SELECT 'text:' || a $$ LANGUAGE sql");
      // A function with OUT parameters: SELECT * FROM som(...) names the result columns after the
      // OUT arguments, so getXXX(name) can read them back by name.
      stmt.execute("CREATE OR REPLACE FUNCTION som(pa int, OUT pb text, OUT pc int8) "
          + "AS $$ BEGIN pb := 'out'; pc := pa + 1; END $$ LANGUAGE plpgsql");
    }
  }

  @AfterEach
  void tearDown() throws Exception {
    try (Statement stmt = con.createStatement()) {
      stmt.execute("DROP FUNCTION IF EXISTS ovl(int)");
      stmt.execute("DROP FUNCTION IF EXISTS ovl(text)");
      stmt.execute("DROP FUNCTION IF EXISTS som(int)");
    } finally {
      TestUtil.closeDB(con);
    }
  }

  @Test
  void overloadResolvedByBindType() throws SQLException {
    try (NamedCall call = NamedCall.prepare(con, "{ call ovl(?) }")) {
      call.set("a", 5);
      call.execute();
      // The scalar result column of a function is named after the function.
      assertEquals("int:5", call.getString("ovl"),
          "the int overload must be chosen for an Integer bind");
    }
    try (NamedCall call = NamedCall.prepare(con, "{ call ovl(?) }")) {
      call.set("a", "hi");
      call.execute();
      assertEquals("text:hi", call.getString("ovl"),
          "the text overload must be chosen for a String bind");
    }
  }

  @Test
  void outParametersReadByName() throws SQLException {
    try (NamedCall call = NamedCall.prepare(con, "{ call som(?) }")) {
      call.set("pa", 7);
      call.execute();
      assertEquals("out", call.getString("pb"));
      assertEquals(8L, call.getLong("pc"));
    }
  }

  @Test
  void generatedSqlUsesNamedNotation() throws SQLException {
    try (NamedCall call = NamedCall.prepare(con, "{ call som(?) }")) {
      call.set("pa", 7);
      call.execute();
      assertTrue(call.generatedSql().contains("pa => ?"),
          "expected named notation, got: " + call.generatedSql());
    }
  }

  @Test
  void unknownParameterNameSurfacesServerError() throws SQLException {
    // A misspelled name is rejected by the server's own name matching, with the routine's real
    // signature in the message — no driver-side catalog lookup needed.
    try (NamedCall call = NamedCall.prepare(con, "{ call som(?) }")) {
      call.set("nope", 7);
      assertThrows(SQLException.class, call::execute);
    }
  }

  /**
   * Minimal driver for the named-notation approach. Collects IN/INOUT binds by name, then on
   * {@link #execute()} emits {@code select * from <routine>(name => ?, ...)} and binds positionally
   * in the emitted order. Results are read back by column name.
   */
  static final class NamedCall implements AutoCloseable {
    private final Connection con;
    private final String routine;
    // Insertion order defines the positional bind order, which is irrelevant to the server under
    // named notation but must agree between the generated SQL and the bind calls.
    private final Map<String, Object> args = new LinkedHashMap<>();
    private @Nullable String generatedSql;
    private @Nullable PreparedStatement ps;
    private @Nullable ResultSet rs;

    private NamedCall(Connection con, String routine) {
      this.con = con;
      this.routine = routine;
    }

    static NamedCall prepare(Connection con, String jdbcCall) {
      return new NamedCall(con, extractRoutineName(jdbcCall));
    }

    void set(String name, Object value) {
      args.put(name, value);
    }

    boolean execute() throws SQLException {
      StringBuilder sql = new StringBuilder("select * from ").append(routine).append('(');
      boolean first = true;
      for (String name : args.keySet()) {
        if (!first) {
          sql.append(", ");
        }
        first = false;
        // Pass the name unquoted so PostgreSQL folds it the same way it folds the declaration.
        sql.append(name).append(" => ?");
      }
      sql.append(')');
      String generatedSql = this.generatedSql = sql.toString();
      PreparedStatement ps = this.ps = con.prepareStatement(generatedSql);
      int i = 1;
      for (Object value : args.values()) {
        ps.setObject(i++, value);
      }
      ResultSet rs = this.rs = ps.executeQuery();
      return rs.next();
    }

    String getString(String column) throws SQLException {
      return requireRs().getString(column);
    }

    long getLong(String column) throws SQLException {
      return requireRs().getLong(column);
    }

    String generatedSql() {
      String generatedSql = this.generatedSql;
      if (generatedSql == null) {
        throw new IllegalStateException("execute() has not been called yet");
      }
      return generatedSql;
    }

    private ResultSet requireRs() {
      ResultSet rs = this.rs;
      if (rs == null) {
        throw new IllegalStateException("execute() has not been called yet");
      }
      return rs;
    }

    @Override
    public void close() throws SQLException {
      ResultSet rs = this.rs;
      if (rs != null) {
        rs.close();
      }
      PreparedStatement ps = this.ps;
      if (ps != null) {
        ps.close();
      }
    }

    /** Extracts the routine name from a {@code { call name(...) }} escape. */
    private static String extractRoutineName(String jdbcCall) {
      String lower = jdbcCall.toLowerCase(Locale.ROOT);
      int call = lower.indexOf("call");
      int open = jdbcCall.indexOf('(', call);
      if (call < 0 || open < 0) {
        throw new IllegalArgumentException("not a { call name(...) } escape: " + jdbcCall);
      }
      return jdbcCall.substring(call + 4, open).trim();
    }
  }
}
