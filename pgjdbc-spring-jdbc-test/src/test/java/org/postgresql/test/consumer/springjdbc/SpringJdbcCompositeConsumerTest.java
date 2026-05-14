/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.consumer.springjdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlOutParameter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.support.AbstractSqlTypeValue;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Execution(ExecutionMode.SAME_THREAD)
@ParameterizedClass
@MethodSource("data")
class SpringJdbcCompositeConsumerTest extends BaseTest4 {
  private JdbcTemplate jdbcTemplate;
  private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

  SpringJdbcCompositeConsumerTest(BinaryMode binaryMode) {
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

      stmt.execute("CREATE TYPE spring_person_name AS (first_name text, last_name text)");
      stmt.execute("CREATE TYPE spring_customer_record AS (customer_id int, full_name spring_person_name, vip boolean)");
      stmt.execute("CREATE TABLE spring_customer_records (id int primary key, payload spring_customer_record)");

      stmt.execute("CREATE TYPE spring_order_line AS (sku text, quantity int)");
      stmt.execute("CREATE TABLE spring_baskets (id int primary key, items spring_order_line[])");
      stmt.execute("CREATE OR REPLACE PROCEDURE spring_transform_order_line(INOUT line spring_order_line) "
          + "LANGUAGE plpgsql AS $$ "
          + "BEGIN "
          + "  line := ROW((line).sku || '-done', (line).quantity + 10)::spring_order_line; "
          + "END "
          + "$$");
      stmt.execute("CREATE OR REPLACE FUNCTION spring_transform_order_line_fn(line spring_order_line) "
          + "RETURNS spring_order_line "
          + "LANGUAGE sql AS $$ "
          + "  SELECT ROW(($1).sku || '-done', ($1).quantity + 10)::spring_order_line "
          + "$$");
      stmt.execute("CREATE TABLE spring_batch_baskets (id int primary key, payload spring_customer_record,"
          + " items spring_order_line[])");

      stmt.execute("CREATE TYPE spring_nullable_customer AS (customer_id int, nickname text, full_name spring_person_name)");
      stmt.execute("CREATE TABLE spring_nullable_customers (id int primary key, payload spring_nullable_customer)");

      stmt.execute("CREATE SCHEMA spring_shadow_a");
      stmt.execute("CREATE SCHEMA spring_shadow_b");
      stmt.execute("CREATE TYPE spring_shadow_a.shipping_state AS (value text)");
      stmt.execute("CREATE TYPE spring_shadow_b.shipping_state AS (value int)");
      stmt.execute("CREATE TABLE spring_shadow_a.orders (id int primary key, state spring_shadow_a.shipping_state)");
      stmt.execute("CREATE TABLE spring_shadow_b.orders (id int primary key, state spring_shadow_b.shipping_state)");
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
    stmt.execute("DROP TABLE IF EXISTS spring_shadow_b.orders");
    stmt.execute("DROP TABLE IF EXISTS spring_shadow_a.orders");
    stmt.execute("DROP SCHEMA IF EXISTS spring_shadow_b CASCADE");
    stmt.execute("DROP SCHEMA IF EXISTS spring_shadow_a CASCADE");
    stmt.execute("DROP TABLE IF EXISTS spring_nullable_customers");
    stmt.execute("DROP TYPE IF EXISTS spring_nullable_customer CASCADE");
    stmt.execute("DROP TABLE IF EXISTS spring_batch_baskets");
    stmt.execute("DROP TABLE IF EXISTS spring_baskets");
    stmt.execute("DROP TYPE IF EXISTS spring_order_line CASCADE");
    stmt.execute("DROP TABLE IF EXISTS spring_customer_records");
    stmt.execute("DROP TYPE IF EXISTS spring_customer_record CASCADE");
    stmt.execute("DROP TYPE IF EXISTS spring_person_name CASCADE");
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    Connection connection = Objects.requireNonNull(con, "BaseTest4 must initialize connection");
    SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
    jdbcTemplate = new JdbcTemplate(dataSource);
    namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(dataSource);

    jdbcTemplate.execute("TRUNCATE TABLE spring_customer_records");
    jdbcTemplate.execute("TRUNCATE TABLE spring_baskets");
    jdbcTemplate.execute("TRUNCATE TABLE spring_batch_baskets");
    jdbcTemplate.execute("TRUNCATE TABLE spring_nullable_customers");
    jdbcTemplate.execute("TRUNCATE TABLE spring_shadow_a.orders");
    jdbcTemplate.execute("TRUNCATE TABLE spring_shadow_b.orders");
    jdbcTemplate.execute("RESET search_path");
  }

  @Test
  void jdbcTemplate_queryForObject_materializesNestedSqlData() {
    jdbcTemplate.update(connection -> {
      PreparedStatement ps =
          connection.prepareStatement("INSERT INTO spring_customer_records (id, payload) VALUES (?, ?)");
      Struct fullName = connection.createStruct("spring_person_name", new Object[]{"Ada", "Lovelace"});
      Struct customer = connection.createStruct("spring_customer_record", new Object[]{42, fullName, true});
      ps.setInt(1, 42);
      ps.setObject(2, customer);
      return ps;
    });

    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put("spring_person_name", SpringPersonName.class);
    typeMap.put("spring_customer_record", SpringCustomerRecord.class);

    SpringCustomerRecord actual = jdbcTemplate.queryForObject(
        "SELECT payload FROM spring_customer_records WHERE id = ?",
        (rs, rowNum) -> (SpringCustomerRecord) rs.getObject(1, typeMap),
        42);

    assertNotNull(actual);
    assertEquals(42, actual.customerId);
    assertNotNull(actual.fullName);
    assertEquals("Ada", actual.fullName.firstName);
    assertEquals("Lovelace", actual.fullName.lastName);
    assertTrue(actual.vip);
  }

  @Test
  void namedParameterJdbcTemplate_bindsCompositeArrayAndReadsSqlDataArray() {
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("id", 1)
        .addValue("items", new AbstractSqlTypeValue() {
          @Override
          protected Object createTypeValue(Connection connection, int sqlType, String typeName)
              throws SQLException {
            Struct first = connection.createStruct("spring_order_line", new Object[]{"sku-10", 1});
            Struct second = connection.createStruct("spring_order_line", new Object[]{"sku-20", 4});
            return connection.createArrayOf("spring_order_line", new Object[]{first, second});
          }
        }, Types.ARRAY, "spring_order_line");

    namedParameterJdbcTemplate.update(
        "INSERT INTO spring_baskets (id, items) VALUES (:id, :items)",
        params);

    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put("spring_order_line", SpringOrderLine.class);

    Object[] actual = jdbcTemplate.queryForObject(
        "SELECT items FROM spring_baskets WHERE id = ?",
        (rs, rowNum) -> {
          Array array = rs.getArray(1);
          return (Object[]) array.getArray(typeMap);
        },
        1);

    assertNotNull(actual);
    assertEquals(2, actual.length);
    SpringOrderLine first = assertInstanceOf(SpringOrderLine.class, actual[0]);
    SpringOrderLine second = assertInstanceOf(SpringOrderLine.class, actual[1]);
    assertEquals("sku-10", first.sku);
    assertEquals(Integer.valueOf(1), first.quantity);
    assertEquals("sku-20", second.sku);
    assertEquals(Integer.valueOf(4), second.quantity);
  }

  @Test
  void jdbcTemplate_callableStatement_roundTripsCompositeInOut() {
    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put("spring_order_line", SpringOrderLine.class);

    SpringOrderLine actual = jdbcTemplate.execute(
        (Connection connection) -> {
          CallableStatement call = connection.prepareCall("call spring_transform_order_line(?)");
          call.setObject(1, new SpringOrderLine("sku-call", 5));
          call.registerOutParameter(1, Types.STRUCT, "spring_order_line");
          return call;
        },
        (CallableStatementCallback<SpringOrderLine>) call -> {
          call.execute();
          return (SpringOrderLine) call.getObject(1, typeMap);
        });

    assertNotNull(actual);
    assertEquals("sku-call-done", actual.sku);
    assertEquals(Integer.valueOf(15), actual.quantity);
  }

  @Test
  void jdbcTemplate_batchUpdate_bindsCompositeAndCompositeArray() {
    int[][] counts = jdbcTemplate.batchUpdate(
        "INSERT INTO spring_batch_baskets (id, payload, items) VALUES (?, ?, ?)",
        Arrays.asList(
            new SpringBatchBasketRow(
                1,
                new Object[]{1, new Object[]{"Ada", "Lovelace"}, true},
                new Object[][]{{"sku-10", 1}, {"sku-20", 4}}
            ),
            new SpringBatchBasketRow(
                2,
                new Object[]{2, new Object[]{"Grace", "Hopper"}, false},
                new Object[][]{{"sku-30", 2}}
            )
        ),
        2,
        (PreparedStatement ps, SpringBatchBasketRow row) -> {
          Connection connection = ps.getConnection();
          Struct fullName = connection.createStruct("spring_person_name", row.fullNameAttributes());
          Struct customer = connection.createStruct("spring_customer_record",
              new Object[]{row.customerId(), fullName, row.vip()});
          Object[] arrayItems = new Object[row.itemAttributes().length];
          for (int i = 0; i < row.itemAttributes().length; i++) {
            arrayItems[i] = connection.createStruct("spring_order_line", row.itemAttributes()[i]);
          }
          Array items = connection.createArrayOf("spring_order_line", arrayItems);
          ps.setInt(1, row.id());
          ps.setObject(2, customer);
          ps.setArray(3, items);
        });

    assertEquals(1, counts.length);
    assertArrayEquals(new int[]{1, 1}, counts[0]);

    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put("spring_person_name", SpringPersonName.class);
    typeMap.put("spring_customer_record", SpringCustomerRecord.class);
    typeMap.put("spring_order_line", SpringOrderLine.class);

    List<SpringBatchBasketResult> actual = jdbcTemplate.query(
        "SELECT payload, items FROM spring_batch_baskets ORDER BY id",
        (rs, rowNum) -> new SpringBatchBasketResult(
            (SpringCustomerRecord) rs.getObject(1, typeMap),
            castOrderLines((Object[]) rs.getArray(2).getArray(typeMap))));

    assertEquals(2, actual.size());
    assertEquals(1, actual.get(0).customer().customerId);
    assertEquals("Ada", actual.get(0).customer().fullName.firstName);
    assertEquals(2, actual.get(0).items().length);
    assertEquals("sku-20", actual.get(0).items()[1].sku);
    assertEquals(2, actual.get(1).customer().customerId);
    assertEquals("Grace", actual.get(1).customer().fullName.firstName);
    assertEquals(1, actual.get(1).items().length);
    assertEquals("sku-30", actual.get(1).items()[0].sku);
  }

  @Test
  void jdbcTemplate_rowTypeReflectsAlterTableChanges() throws SQLException {
    jdbcTemplate.execute("DROP TABLE IF EXISTS spring_alterable_rowtype");
    try {
      jdbcTemplate.execute("CREATE TABLE spring_alterable_rowtype (id int primary key, status text)");
      jdbcTemplate.update("INSERT INTO spring_alterable_rowtype VALUES (?, ?)", 1, "new");

      Struct before = jdbcTemplate.queryForObject(
          "SELECT r FROM spring_alterable_rowtype r WHERE id = ?",
          (rs, rowNum) -> rs.getObject(1, Struct.class),
          1);
      assertNotNull(before);
      assertArrayEquals(new Object[]{1, "new"}, before.getAttributes());

      jdbcTemplate.execute("ALTER TABLE spring_alterable_rowtype ADD COLUMN priority int");
      jdbcTemplate.update("UPDATE spring_alterable_rowtype SET priority = ? WHERE id = ?", 9, 1);

      Struct after = jdbcTemplate.queryForObject(
          "SELECT r FROM spring_alterable_rowtype r WHERE id = ?",
          (rs, rowNum) -> rs.getObject(1, Struct.class),
          1);
      assertNotNull(after);
      assertArrayEquals(new Object[]{1, "new", 9}, after.getAttributes());
    } finally {
      jdbcTemplate.execute("DROP TABLE IF EXISTS spring_alterable_rowtype");
    }
  }

  @Test
  void jdbcTemplate_preservesNullHeavyNestedComposites() throws SQLException {
    jdbcTemplate.update(connection -> {
      PreparedStatement ps =
          connection.prepareStatement("INSERT INTO spring_nullable_customers (id, payload) VALUES (?, ?)");
      Struct sparseName = connection.createStruct("spring_person_name", new Object[]{null, "Solo"});
      Struct payload = connection.createStruct("spring_nullable_customer", new Object[]{77, null, sparseName});
      ps.setInt(1, 1);
      ps.setObject(2, payload);
      return ps;
    });

    jdbcTemplate.update(connection -> {
      PreparedStatement ps =
          connection.prepareStatement("INSERT INTO spring_nullable_customers (id, payload) VALUES (?, ?)");
      Struct payload = connection.createStruct("spring_nullable_customer", new Object[]{78, "ghost", null});
      ps.setInt(1, 2);
      ps.setObject(2, payload);
      return ps;
    });

    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put("spring_person_name", SpringPersonName.class);
    typeMap.put("spring_nullable_customer", SpringNullableCustomer.class);

    SpringNullableCustomer first = jdbcTemplate.queryForObject(
        "SELECT payload FROM spring_nullable_customers WHERE id = ?",
        (rs, rowNum) -> (SpringNullableCustomer) rs.getObject(1, typeMap),
        1);
    assertNotNull(first);
    assertEquals(Integer.valueOf(77), first.customerId);
    assertNull(first.nickname);
    assertNotNull(first.fullName);
    assertNull(first.fullName.firstName);
    assertEquals("Solo", first.fullName.lastName);

    Struct second = jdbcTemplate.queryForObject(
        "SELECT payload FROM spring_nullable_customers WHERE id = ?",
        (rs, rowNum) -> rs.getObject(1, Struct.class),
        2);
    assertNotNull(second);
    assertArrayEquals(new Object[]{78, "ghost", null}, second.getAttributes());
  }

  @Test
  void springCallbacks_respectSearchPathForShadowedTypeNames() throws SQLException {
    jdbcTemplate.execute("SET search_path TO spring_shadow_a");
    jdbcTemplate.update(connection -> {
      PreparedStatement ps =
          connection.prepareStatement("INSERT INTO spring_shadow_a.orders (id, state) VALUES (?, ?)");
      Struct state = connection.createStruct("shipping_state", new Object[]{"ready"});
      ps.setInt(1, 1);
      ps.setObject(2, state);
      return ps;
    });

    Struct fromA = jdbcTemplate.queryForObject(
        "SELECT state FROM spring_shadow_a.orders WHERE id = ?",
        (rs, rowNum) -> rs.getObject(1, Struct.class),
        1);
    assertNotNull(fromA);
    assertArrayEquals(new Object[]{"ready"}, fromA.getAttributes());

    jdbcTemplate.execute("SET search_path TO spring_shadow_b");
    jdbcTemplate.update(connection -> {
      PreparedStatement ps =
          connection.prepareStatement("INSERT INTO spring_shadow_b.orders (id, state) VALUES (?, ?)");
      Struct state = connection.createStruct("shipping_state", new Object[]{7});
      ps.setInt(1, 1);
      ps.setObject(2, state);
      return ps;
    });

    Struct fromB = jdbcTemplate.queryForObject(
        "SELECT state FROM spring_shadow_b.orders WHERE id = ?",
        (rs, rowNum) -> rs.getObject(1, Struct.class),
        1);
    assertNotNull(fromB);
    assertArrayEquals(new Object[]{7}, fromB.getAttributes());
  }

  @Test
  void compositeArrays_preserveNullElementAndEmptyArray() {
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement("INSERT INTO spring_baskets (id, items) VALUES (?, ?)");
      Struct first = connection.createStruct("spring_order_line", new Object[]{"sku-10", 1});
      Struct third = connection.createStruct("spring_order_line", new Object[]{"sku-30", 3});
      Array items = connection.createArrayOf("spring_order_line", new Object[]{first, null, third});
      ps.setInt(1, 10);
      ps.setArray(2, items);
      return ps;
    });

    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement("INSERT INTO spring_baskets (id, items) VALUES (?, ?)");
      Array items = connection.createArrayOf("spring_order_line", new Object[0]);
      ps.setInt(1, 11);
      ps.setArray(2, items);
      return ps;
    });

    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put("spring_order_line", SpringOrderLine.class);

    Object[] withNull = jdbcTemplate.queryForObject(
        "SELECT items FROM spring_baskets WHERE id = ?",
        (rs, rowNum) -> (Object[]) rs.getArray(1).getArray(typeMap),
        10);
    assertNotNull(withNull);
    assertEquals(3, withNull.length);
    SpringOrderLine first = assertInstanceOf(SpringOrderLine.class, withNull[0]);
    assertNull(withNull[1]);
    SpringOrderLine third = assertInstanceOf(SpringOrderLine.class, withNull[2]);
    assertEquals("sku-10", first.sku);
    assertEquals(Integer.valueOf(1), first.quantity);
    assertEquals("sku-30", third.sku);
    assertEquals(Integer.valueOf(3), third.quantity);

    Object[] empty = jdbcTemplate.queryForObject(
        "SELECT items FROM spring_baskets WHERE id = ?",
        (rs, rowNum) -> (Object[]) rs.getArray(1).getArray(typeMap),
        11);
    assertNotNull(empty);
    assertEquals(0, empty.length);
  }

  @Test
  void simpleJdbcCall_roundTripsCompositeInOut() throws SQLException {
    SimpleJdbcCall call = new SimpleJdbcCall(jdbcTemplate)
        .withFunctionName("spring_transform_order_line_fn")
        .withoutProcedureColumnMetaDataAccess()
        .declareParameters(
            new SqlOutParameter("return", Types.STRUCT, "spring_order_line"),
            new org.springframework.jdbc.core.SqlParameter("line", Types.STRUCT, "spring_order_line"));

    Struct struct = call.executeFunction(
        Struct.class,
        new MapSqlParameterSource()
        .addValue("line", new SpringSqlStructValue("spring_order_line", new Object[]{"sku-simple", 9}),
            Types.STRUCT, "spring_order_line"));

    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put("spring_order_line", SpringOrderLine.class);

    assertNotNull(struct);
    SpringOrderLine actual = toOrderLine(struct, typeMap);
    assertEquals("sku-simple-done", actual.sku);
    assertEquals(Integer.valueOf(19), actual.quantity);
  }

  private static SpringOrderLine[] castOrderLines(Object[] raw) {
    SpringOrderLine[] result = new SpringOrderLine[raw.length];
    for (int i = 0; i < raw.length; i++) {
      result[i] = (SpringOrderLine) raw[i];
    }
    return result;
  }

  private static SpringOrderLine toOrderLine(Struct struct, Map<String, Class<?>> typeMap) throws SQLException {
    Object mapped = struct.getAttributes(typeMap);
    if (mapped instanceof Object[]) {
      Object[] attrs = (Object[]) mapped;
      SpringOrderLine line = new SpringOrderLine();
      line.sku = (String) attrs[0];
      line.quantity = (Integer) attrs[1];
      return line;
    }
    throw new SQLException("Unexpected mapped struct payload: " + mapped);
  }

  public static final class SpringPersonName implements SQLData {
    String firstName;
    String lastName;

    @Override
    public String getSQLTypeName() {
      return "spring_person_name";
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

  public static final class SpringCustomerRecord implements SQLData {
    int customerId;
    SpringPersonName fullName;
    boolean vip;

    @Override
    public String getSQLTypeName() {
      return "spring_customer_record";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      customerId = stream.readInt();
      fullName = (SpringPersonName) stream.readObject();
      vip = stream.readBoolean();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeInt(customerId);
      stream.writeObject(fullName);
      stream.writeBoolean(vip);
    }
  }

  public static final class SpringOrderLine implements SQLData {
    String sku;
    Integer quantity;

    public SpringOrderLine() {
    }

    SpringOrderLine(String sku, Integer quantity) {
      this.sku = sku;
      this.quantity = quantity;
    }

    @Override
    public String getSQLTypeName() {
      return "spring_order_line";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      sku = stream.readString();
      int rawQuantity = stream.readInt();
      quantity = stream.wasNull() ? null : rawQuantity;
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeString(sku);
      stream.writeInt(quantity);
    }
  }

  public static final class SpringNullableCustomer implements SQLData {
    Integer customerId;
    String nickname;
    SpringPersonName fullName;

    @Override
    public String getSQLTypeName() {
      return "spring_nullable_customer";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      int rawCustomerId = stream.readInt();
      customerId = stream.wasNull() ? null : rawCustomerId;
      nickname = stream.readString();
      fullName = (SpringPersonName) stream.readObject();
    }

    @Override
    public void writeSQL(SQLOutput stream) {
      throw new UnsupportedOperationException("write path is not exercised in this regression test");
    }
  }

  private static final class SpringBatchBasketRow {
    private final int id;
    private final Object[] customerAttributes;
    private final Object[][] itemAttributes;

    private SpringBatchBasketRow(int id, Object[] customerAttributes, Object[][] itemAttributes) {
      this.id = id;
      this.customerAttributes = customerAttributes;
      this.itemAttributes = itemAttributes;
    }

    int id() {
      return id;
    }

    int customerId() {
      return (Integer) customerAttributes[0];
    }

    Object[] fullNameAttributes() {
      return (Object[]) customerAttributes[1];
    }

    boolean vip() {
      return (Boolean) customerAttributes[2];
    }

    Object[][] itemAttributes() {
      return itemAttributes;
    }
  }

  private static final class SpringBatchBasketResult {
    private final SpringCustomerRecord customer;
    private final SpringOrderLine[] items;

    private SpringBatchBasketResult(SpringCustomerRecord customer, SpringOrderLine[] items) {
      this.customer = customer;
      this.items = items;
    }

    SpringCustomerRecord customer() {
      return customer;
    }

    SpringOrderLine[] items() {
      return items;
    }
  }

  private static final class SpringSqlStructValue extends AbstractSqlTypeValue {
    private final String typeName;
    private final Object[] attributes;

    private SpringSqlStructValue(String typeName, Object[] attributes) {
      this.typeName = typeName;
      this.attributes = attributes;
    }

    @Override
    protected Object createTypeValue(Connection connection, int sqlType, String typeName) throws SQLException {
      return connection.createStruct(this.typeName, attributes);
    }
  }
}
