/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.test.util.CountingSocketFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Properties;

/**
 * Tests that {@code getParameterMetaData} reuses the types a previous describe resolved instead of
 * describing the statement again.
 */
public class ParameterMetaDataCacheTest extends BaseTest4 {
  private final CountingSocketFactory.Counters socketCounters = CountingSocketFactory.register();

  @BeforeAll
  static void createTables() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.createTable(con, "pmdcachetest", "a int4, b text");
      TestUtil.createTable(con, "pmdcacheddl", "a int4");
      // Two candidates for the same call make the parameter types ambiguous until the driver tells
      // the server the type of at least one parameter.
      TestUtil.execute(con, "create or replace function pmdcacheoverload(int4, int4) "
          + "returns int4 as 'select 1' language sql");
      TestUtil.execute(con, "create or replace function pmdcacheoverload(varchar, varchar) "
          + "returns int4 as 'select 2' language sql");
    }
  }

  @AfterAll
  static void dropTables() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.execute(con, "drop function if exists pmdcacheoverload(int4, int4)");
      TestUtil.execute(con, "drop function if exists pmdcacheoverload(varchar, varchar)");
      TestUtil.dropTable(con, "pmdcacheddl");
      TestUtil.dropTable(con, "pmdcachetest");
    }
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.SOCKET_FACTORY.set(props, CountingSocketFactory.class.getName());
    PGProperty.SOCKET_FACTORY_ARG.set(props, socketCounters.key());
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    assumeTrue(preferQueryMode != PreferQueryMode.SIMPLE,
        "simple protocol does not support describe statement requests");
  }

  @Override
  protected void tearDown() throws SQLException {
    try {
      super.tearDown();
    } finally {
      CountingSocketFactory.unregister(socketCounters);
    }
  }

  @Test
  void repeatedCallsDescribeOnce() throws SQLException {
    try (PreparedStatement ps =
        con.prepareStatement("select * from pmdcachetest where a = ? and b = ?")) {
      long beforeFirst = socketCounters.roundtrips.get();
      assertEquals(Types.INTEGER, ps.getParameterMetaData().getParameterType(1));
      long afterFirst = socketCounters.roundtrips.get();
      assertEquals(1, afterFirst - beforeFirst,
          "the first getParameterMetaData has to describe the statement");

      assertEquals(Types.INTEGER, ps.getParameterMetaData().getParameterType(1));
      assertEquals(0, socketCounters.roundtrips.get() - afterFirst,
          "the second getParameterMetaData should reuse the resolved types");
    }
  }

  @Test
  void resolvedTypesOutliveTheStatement() throws SQLException {
    String sql = "select * from pmdcachetest where a = ?";
    try (PreparedStatement ps = con.prepareStatement(sql)) {
      ps.getParameterMetaData();
    }

    long before = socketCounters.roundtrips.get();
    try (PreparedStatement ps = con.prepareStatement(sql)) {
      assertEquals(Types.INTEGER, ps.getParameterMetaData().getParameterType(1));
    }
    assertEquals(0, socketCounters.roundtrips.get() - before,
        "the types are cached per query, so a new statement for the same SQL should not describe "
            + "it again");
  }

  @Test
  void executionDoesNotDropTheResolvedTypes() throws SQLException {
    String sql = "select * from pmdcachetest where a = ?";
    try (PreparedStatement ps = con.prepareStatement(sql)) {
      ps.getParameterMetaData();
      ps.setInt(1, 1);
      ps.executeQuery().close();

      long before = socketCounters.roundtrips.get();
      assertEquals(Types.INTEGER, ps.getParameterMetaData().getParameterType(1));
      assertEquals(0, socketCounters.roundtrips.get() - before,
          "executing the query should not invalidate the resolved parameter types");
    }
  }

  @Test
  void knownTypesArePartOfTheKey() throws SQLException {
    String sql = "select pmdcacheoverload(?, ?)";
    try (PreparedStatement ps = con.prepareStatement(sql)) {
      // With no type to go by, the server picks the varchar candidate.
      assertEquals(Types.VARCHAR, ps.getParameterMetaData().getParameterType(1));
    }

    try (PreparedStatement ps = con.prepareStatement(sql)) {
      // int4 for the first parameter picks the int4 candidate instead, and the server infers int4
      // for the second one. Keying the cache on the query alone would report varchar here.
      ps.setInt(1, 1);
      assertEquals(Types.INTEGER, ps.getParameterMetaData().getParameterType(2),
          "the types resolved with no known type must not answer a call that knows the first "
              + "parameter is int4");
    }
  }

  @Test
  void ddlDropsTheResolvedTypes() throws SQLException {
    String sql = "select * from pmdcacheddl where a = ?";
    try (PreparedStatement ps = con.prepareStatement(sql)) {
      assertEquals(Types.INTEGER, ps.getParameterMetaData().getParameterType(1));
    }

    try (Statement statement = con.createStatement()) {
      statement.execute("alter table pmdcacheddl alter column a type bigint");
    }

    long before = socketCounters.roundtrips.get();
    try (PreparedStatement ps = con.prepareStatement(sql)) {
      assertEquals(Types.BIGINT, ps.getParameterMetaData().getParameterType(1),
          "DDL invalidates the cached plans, so the types have to be resolved again");
    }
    assertEquals(1, socketCounters.roundtrips.get() - before,
        "the statement has to be described again after DDL");
  }
}
