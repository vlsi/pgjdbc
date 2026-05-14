/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.consumer.composite;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Statement;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

@Execution(ExecutionMode.SAME_THREAD)
@ParameterizedClass
@MethodSource("data")
class CompositeConsumerRoundtripTest extends BaseTest4 {
  private static final String QUOTED_SCHEMA = "\"ConsumerCaseSchema\"";
  private static final String QUOTED_ADDRESS_TYPE = QUOTED_SCHEMA + ".\"PostalAddress\"";
  private static final String QUOTED_TABLE = QUOTED_SCHEMA + ".\"QuotedOrders\"";

  public CompositeConsumerRoundtripTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @BeforeAll
  static void setUpSchema() throws SQLException {
    try (Connection conn = TestUtil.openDB();
         Statement stmt = conn.createStatement()) {
      cleanup(stmt);

      stmt.execute("CREATE SCHEMA " + QUOTED_SCHEMA);
      stmt.execute("CREATE TYPE " + QUOTED_ADDRESS_TYPE
          + " AS (\"streetLine\" text, \"postalCode\" text)");
      stmt.execute("CREATE TABLE " + QUOTED_TABLE
          + " (id int primary key, \"shippingAddress\" " + QUOTED_ADDRESS_TYPE + ")");

      stmt.execute("CREATE TYPE consumer_person_name AS (first_name text, last_name text)");
      stmt.execute("CREATE TYPE consumer_customer_record AS (customer_id int, full_name consumer_person_name, vip boolean)");
      stmt.execute("CREATE TABLE consumer_customer_records (id int primary key, payload consumer_customer_record)");

      stmt.execute("CREATE TYPE consumer_order_line AS (sku text, quantity int)");
      stmt.execute("CREATE TABLE consumer_baskets (id int primary key, items consumer_order_line[])");

      stmt.execute("CREATE TYPE consumer_batch_customer AS (email text, loyalty_tier int)");
      stmt.execute("CREATE TABLE consumer_batch_events (id int primary key, customer consumer_batch_customer)");

      stmt.execute("CREATE SCHEMA consumer_shadow_a");
      stmt.execute("CREATE SCHEMA consumer_shadow_b");
      stmt.execute("CREATE TYPE consumer_shadow_a.shipping_state AS (value text)");
      stmt.execute("CREATE TYPE consumer_shadow_b.shipping_state AS (value int)");
      stmt.execute("CREATE TABLE consumer_shadow_a.orders (id int primary key, state consumer_shadow_a.shipping_state)");
      stmt.execute("CREATE TABLE consumer_shadow_b.orders (id int primary key, state consumer_shadow_b.shipping_state)");
    }
  }

  @AfterAll
  static void tearDownSchema() throws SQLException {
    try (Connection conn = TestUtil.openDB();
         Statement stmt = conn.createStatement()) {
      cleanup(stmt);
    }
  }

  private static void cleanup(Statement stmt) throws SQLException {
    stmt.execute("DROP TABLE IF EXISTS consumer_shadow_b.orders");
    stmt.execute("DROP TABLE IF EXISTS consumer_shadow_a.orders");
    stmt.execute("DROP SCHEMA IF EXISTS consumer_shadow_b CASCADE");
    stmt.execute("DROP SCHEMA IF EXISTS consumer_shadow_a CASCADE");
    stmt.execute("DROP TABLE IF EXISTS consumer_batch_events");
    stmt.execute("DROP TYPE IF EXISTS consumer_batch_customer CASCADE");
    stmt.execute("DROP TABLE IF EXISTS consumer_baskets");
    stmt.execute("DROP TYPE IF EXISTS consumer_order_line CASCADE");
    stmt.execute("DROP TABLE IF EXISTS consumer_customer_records");
    stmt.execute("DROP TYPE IF EXISTS consumer_customer_record CASCADE");
    stmt.execute("DROP TYPE IF EXISTS consumer_person_name CASCADE");
    stmt.execute("DROP TABLE IF EXISTS " + QUOTED_TABLE);
    stmt.execute("DROP TYPE IF EXISTS " + QUOTED_ADDRESS_TYPE + " CASCADE");
    stmt.execute("DROP SCHEMA IF EXISTS " + QUOTED_SCHEMA + " CASCADE");
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    try (Statement stmt = con.createStatement()) {
      stmt.execute("TRUNCATE TABLE " + QUOTED_TABLE);
      stmt.execute("TRUNCATE TABLE consumer_customer_records");
      stmt.execute("TRUNCATE TABLE consumer_baskets");
      stmt.execute("TRUNCATE TABLE consumer_batch_events");
      stmt.execute("TRUNCATE TABLE consumer_shadow_a.orders");
      stmt.execute("TRUNCATE TABLE consumer_shadow_b.orders");
      stmt.execute("RESET search_path");
    }
  }

  @Test
  void quotedIdentifiers_roundTripViaGetObjectMap() throws SQLException {
    try (PreparedStatement insert = con.prepareStatement(
        "INSERT INTO " + QUOTED_TABLE + " (id, \"shippingAddress\") VALUES (?, ?)");
         PreparedStatement select = con.prepareStatement(
             "SELECT \"shippingAddress\" FROM " + QUOTED_TABLE + " WHERE id = ?")) {
      Struct address = con.createStruct(QUOTED_ADDRESS_TYPE, new Object[]{"221B Baker Street", "NW1"});
      insert.setInt(1, 1);
      insert.setObject(2, address);
      assertEquals(1, insert.executeUpdate());

      Map<String, Class<?>> typeMap = new HashMap<>();
      typeMap.put(QUOTED_ADDRESS_TYPE, QuotedPostalAddress.class);

      select.setInt(1, 1);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        QuotedPostalAddress actual =
            (QuotedPostalAddress) rs.getObject(1, typeMap);
        assertEquals("221B Baker Street", actual.streetLine);
        assertEquals("NW1", actual.postalCode);
      }
    }
  }

  @Test
  void searchPathSwitch_updatesUnqualifiedCreateStructResolution() throws SQLException {
    try (Statement stmt = con.createStatement();
         PreparedStatement insertA = con.prepareStatement(
             "INSERT INTO consumer_shadow_a.orders (id, state) VALUES (?, ?)");
         PreparedStatement insertB = con.prepareStatement(
             "INSERT INTO consumer_shadow_b.orders (id, state) VALUES (?, ?)");
         PreparedStatement selectA = con.prepareStatement(
             "SELECT state FROM consumer_shadow_a.orders WHERE id = ?");
         PreparedStatement selectB = con.prepareStatement(
             "SELECT state FROM consumer_shadow_b.orders WHERE id = ?")) {
      stmt.execute("SET search_path TO consumer_shadow_a");
      Struct textState = con.createStruct("shipping_state", new Object[]{"ready"});
      insertA.setInt(1, 1);
      insertA.setObject(2, textState);
      assertEquals(1, insertA.executeUpdate());

      selectA.setInt(1, 1);
      try (ResultSet rs = selectA.executeQuery()) {
        assertTrue(rs.next());
        Struct actual = rs.getObject(1, Struct.class);
        assertEquals("ready", actual.getAttributes()[0]);
      }

      stmt.execute("SET search_path TO consumer_shadow_b");
      Struct intState = con.createStruct("shipping_state", new Object[]{7});
      insertB.setInt(1, 1);
      insertB.setObject(2, intState);
      assertEquals(1, insertB.executeUpdate());

      selectB.setInt(1, 1);
      try (ResultSet rs = selectB.executeQuery()) {
        assertTrue(rs.next());
        Struct actual = rs.getObject(1, Struct.class);
        assertEquals(7, actual.getAttributes()[0]);
      }
    } finally {
      try (Statement reset = con.createStatement()) {
        reset.execute("RESET search_path");
      }
    }
  }

  @Test
  void nestedComposite_getObjectMapBuildsNestedSqlData() throws SQLException {
    Struct fullName = con.createStruct("consumer_person_name", new Object[]{"Ada", "Lovelace"});
    Struct customer = con.createStruct("consumer_customer_record", new Object[]{42, fullName, true});

    try (PreparedStatement insert = con.prepareStatement(
        "INSERT INTO consumer_customer_records (id, payload) VALUES (?, ?)");
         PreparedStatement select = con.prepareStatement(
             "SELECT payload FROM consumer_customer_records WHERE id = ?")) {
      insert.setInt(1, 42);
      insert.setObject(2, customer);
      assertEquals(1, insert.executeUpdate());

      Map<String, Class<?>> typeMap = new HashMap<>();
      typeMap.put("consumer_person_name", ConsumerPersonName.class);
      typeMap.put("consumer_customer_record", ConsumerCustomerRecord.class);

      select.setInt(1, 42);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        ConsumerCustomerRecord actual =
            (ConsumerCustomerRecord) rs.getObject(1, typeMap);
        assertEquals(42, actual.customerId);
        assertNotNull(actual.fullName);
        assertEquals("Ada", actual.fullName.firstName);
        assertEquals("Lovelace", actual.fullName.lastName);
        assertTrue(actual.vip);
      }
    }
  }

  @Test
  void arraysOfComposites_roundTripAsStructElements() throws SQLException {
    Struct first = con.createStruct("consumer_order_line", new Object[]{"sku-1", 2});
    Struct second = con.createStruct("consumer_order_line", new Object[]{"sku-2", 5});
    Array items = con.createArrayOf("consumer_order_line", new Object[]{first, second});

    try (PreparedStatement insert = con.prepareStatement(
        "INSERT INTO consumer_baskets (id, items) VALUES (?, ?)");
         PreparedStatement select = con.prepareStatement(
             "SELECT items FROM consumer_baskets WHERE id = ?")) {
      insert.setInt(1, 1);
      insert.setArray(2, items);
      assertEquals(1, insert.executeUpdate());

      select.setInt(1, 1);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        Object[] actual = (Object[]) rs.getArray(1).getArray();
        assertEquals(2, actual.length);

        Struct actualFirst = assertInstanceOf(Struct.class, actual[0]);
        Struct actualSecond = assertInstanceOf(Struct.class, actual[1]);
        assertArrayEquals(new Object[]{"sku-1", 2}, actualFirst.getAttributes());
        assertArrayEquals(new Object[]{"sku-2", 5}, actualSecond.getAttributes());
      }
    }
  }

  @Test
  void batchInsert_sqlDataValuesRemainReadable() throws SQLException {
    try (PreparedStatement insert = con.prepareStatement(
        "INSERT INTO consumer_batch_events (id, customer) VALUES (?, ?)");
         PreparedStatement select = con.prepareStatement(
             "SELECT customer FROM consumer_batch_events WHERE id = ?")) {
      insert.setInt(1, 1);
      insert.setObject(2, new BatchCustomer("alpha@example.test", 1));
      insert.addBatch();

      insert.setInt(1, 2);
      insert.setObject(2, new BatchCustomer("beta@example.test", 3));
      insert.addBatch();

      assertArrayEquals(new int[]{1, 1}, insert.executeBatch());

      Map<String, Class<?>> typeMap = new HashMap<>();
      typeMap.put("consumer_batch_customer", BatchCustomer.class);

      select.setInt(1, 2);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        BatchCustomer actual = (BatchCustomer) rs.getObject(1, typeMap);
        assertEquals("beta@example.test", actual.email);
        assertEquals(3, actual.loyaltyTier);
      }
    }
  }

  public static final class QuotedPostalAddress implements SQLData {
    String streetLine;
    String postalCode;

    @Override
    public String getSQLTypeName() {
      return QUOTED_ADDRESS_TYPE;
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      streetLine = stream.readString();
      postalCode = stream.readString();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeString(streetLine);
      stream.writeString(postalCode);
    }
  }

  public static final class ConsumerPersonName implements SQLData {
    String firstName;
    String lastName;

    @Override
    public String getSQLTypeName() {
      return "consumer_person_name";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      firstName = stream.readString();
      lastName = stream.readString();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeString(firstName);
      stream.writeString(lastName);
    }
  }

  public static final class ConsumerCustomerRecord implements SQLData {
    int customerId;
    ConsumerPersonName fullName;
    boolean vip;

    @Override
    public String getSQLTypeName() {
      return "consumer_customer_record";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      customerId = stream.readInt();
      fullName = (ConsumerPersonName) stream.readObject();
      vip = stream.readBoolean();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeInt(customerId);
      stream.writeObject(fullName);
      stream.writeBoolean(vip);
    }
  }

  public static final class BatchCustomer implements SQLData {
    String email;
    int loyaltyTier;

    public BatchCustomer() {
    }

    BatchCustomer(String email, int loyaltyTier) {
      this.email = email;
      this.loyaltyTier = loyaltyTier;
    }

    @Override
    public String getSQLTypeName() {
      return "consumer_batch_customer";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      email = stream.readString();
      loyaltyTier = stream.readInt();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeString(email);
      stream.writeInt(loyaltyTier);
    }
  }
}
