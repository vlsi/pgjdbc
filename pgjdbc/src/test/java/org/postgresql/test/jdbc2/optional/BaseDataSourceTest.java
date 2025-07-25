/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2.optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.PGConnection;
import org.postgresql.ds.common.BaseDataSource;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.test.util.MiniJndiContextFactory;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Hashtable;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

/**
 * Common tests for all the BaseDataSource implementations. This is a small variety to make sure
 * that a connection can be opened and some basic queries run. The different BaseDataSource
 * subclasses have different subclasses of this which add additional custom tests.
 *
 * @author Aaron Mulder (ammulder@chariotsolutions.com)
 */
public abstract class BaseDataSourceTest extends BaseTest4 {
  public static final String DATA_SOURCE_JNDI = "BaseDataSource";

  protected BaseDataSource bds;

  /**
   * Creates a test table using a standard connection (not from a DataSource).
   */
  @Override
  public void setUp() throws Exception {
    con = TestUtil.openDB();
    TestUtil.createTable(con, "poolingtest", "id int4 not null primary key, name varchar(50)");
    Statement stmt = con.createStatement();
    stmt.executeUpdate("INSERT INTO poolingtest VALUES (1, 'Test Row 1')");
    stmt.executeUpdate("INSERT INTO poolingtest VALUES (2, 'Test Row 2')");
    TestUtil.closeDB(con);
  }

  /**
   * Removes the test table using a standard connection (not from a DataSource).
   */
  @Override
  public void tearDown() throws SQLException {
    TestUtil.closeDB(con);
    con = TestUtil.openDB();
    TestUtil.dropTable(con, "poolingtest");
    TestUtil.closeDB(con);
  }

  /**
   * Gets a connection from the current BaseDataSource.
   */
  protected Connection getDataSourceConnection() throws SQLException {
    if (bds == null) {
      initializeDataSource();
    }
    return bds.getConnection();
  }

  /**
   * Creates an instance of the current BaseDataSource for testing. Must be customized by each
   * subclass.
   */
  protected abstract void initializeDataSource() throws PSQLException;

  public static void setupDataSource(BaseDataSource bds) throws PSQLException {
    bds.setServerName(TestUtil.getServer());
    bds.setPortNumber(TestUtil.getPort());
    bds.setDatabaseName(TestUtil.getDatabase());
    bds.setUser(TestUtil.getUser());
    bds.setPassword(TestUtil.getPassword());
    bds.setPrepareThreshold(TestUtil.getPrepareThreshold());
    bds.setProtocolVersion(TestUtil.getProtocolVersion());
  }

  /**
   * Test to make sure you can instantiate and configure the appropriate DataSource.
   */
  @Test
  public void testCreateDataSource() throws PSQLException {
    initializeDataSource();
  }

  /**
   * Test to make sure you can get a connection from the DataSource, which in turn means the
   * DataSource was able to open it.
   */
  @Test
  public void testGetConnection() {
    try {
      con = getDataSourceConnection();
      con.close();
    } catch (SQLException e) {
      fail(e.getMessage());
    }
  }

  /**
   * A simple test to make sure you can execute SQL using the Connection from the DataSource.
   */
  @Test
  public void testUseConnection() {
    try {
      con = getDataSourceConnection();
      Statement st = con.createStatement();
      ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM poolingtest");
      if (rs.next()) {
        int count = rs.getInt(1);
        if (rs.next()) {
          fail("Should only have one row in SELECT COUNT result set");
        }
        if (count != 2) {
          fail("Count returned " + count + " expecting 2");
        }
      } else {
        fail("Should have one row in SELECT COUNT result set");
      }
      rs.close();
      st.close();
      con.close();
    } catch (SQLException e) {
      fail(e.getMessage());
    }
  }

  /**
   * A test to make sure you can execute DDL SQL using the Connection from the DataSource.
   */
  @Test
  public void testDdlOverConnection() {
    try {
      con = getDataSourceConnection();
      TestUtil.createTable(con, "poolingtest", "id int4 not null primary key, name varchar(50)");
      con.close();
    } catch (SQLException e) {
      fail(e.getMessage());
    }
  }

  /**
   * A test to make sure the connections are not being pooled by the current DataSource. Obviously
   * need to be overridden in the case of a pooling Datasource.
   */
  @Test
  public void testNotPooledConnection() throws SQLException {
    Connection con1 = getDataSourceConnection();
    con1.close();
    Connection con2 = getDataSourceConnection();
    con2.close();
    assertNotSame(con1, con2);
  }

  /**
   * Test to make sure that PGConnection methods can be called on the pooled Connection.
   */
  @Test
  public void testPGConnection() {
    try {
      con = getDataSourceConnection();
      ((PGConnection) con).getNotifications();
      con.close();
    } catch (Exception e) {
      fail("Unable to call PGConnection method on pooled connection due to "
          + e.getClass().getName() + " (" + e.getMessage() + ")");
    }
  }

  /**
   * Eventually, we must test stuffing the DataSource in JNDI and then getting it back out and make
   * sure it's still usable. This should ideally test both Serializable and Referenceable
   * mechanisms. Will probably be multiple tests when implemented.
   */
  @Test
  public void testJndi() throws PSQLException {
    initializeDataSource();
    BaseDataSource oldbds = bds;
    String oldurl = bds.getURL();
    InitialContext ic = getInitialContext();
    try {
      ic.rebind(DATA_SOURCE_JNDI, bds);
      bds = (BaseDataSource) ic.lookup(DATA_SOURCE_JNDI);
      assertNotNull(bds, "Got null looking up DataSource from JNDI!");
      compareJndiDataSource(oldbds, bds);
    } catch (NamingException e) {
      fail(e.getMessage());
    }
    oldbds = bds;
    String url = bds.getURL();
    testUseConnection();
    assertSame(oldbds, bds, "Test should not have changed DataSource (" + bds + " != " + oldbds + ")!");
    assertEquals(oldurl, url, "Test should not have changed DataSource URL");
  }

  /**
   * Uses the mini-JNDI implementation for testing purposes.
   */
  protected static InitialContext getInitialContext() {
    Hashtable<String, Object> env = new Hashtable<>();
    env.put(Context.INITIAL_CONTEXT_FACTORY, MiniJndiContextFactory.class.getName());
    try {
      return new InitialContext(env);
    } catch (NamingException e) {
      fail("Unable to create InitialContext: " + e.getMessage());
      return null;
    }
  }

  /**
   * Check whether a DS was dereferenced from JNDI or recreated.
   */
  protected void compareJndiDataSource(BaseDataSource oldbds, BaseDataSource bds) {
    assertNotSame(oldbds, bds, "DataSource was dereferenced, should have been serialized or recreated");
  }
}
