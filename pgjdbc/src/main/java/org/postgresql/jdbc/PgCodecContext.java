/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.api.Experimental;
import org.postgresql.api.codec.BackpatchingByteArrayOutputStream;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.CodecContextBuilder;
import org.postgresql.api.codec.CodecLookup;
import org.postgresql.api.codec.CodecValueFactory;
import org.postgresql.api.codec.IntervalStyle;
import org.postgresql.api.codec.PrefersJavaTime;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.Encoding;
import org.postgresql.core.TypeInfo;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.Struct;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * Connection-bound implementation of {@link CodecContext}.
 *
 * <p>Beyond the public {@link CodecContext} surface, this class exposes the driver internals the
 * built-in container codecs and SQLData adapters need:</p>
 * <ul>
 *   <li>{@link TypeInfo} - PostgreSQL type metadata cache</li>
 *   <li>{@link CodecRegistry} - Codec lookup and registration</li>
 *   <li>{@link JavaTypeRegistry} - Java class to PostgreSQL type mappings</li>
 *   <li>{@link BaseConnection} and {@link Encoding}</li>
 * </ul>
 *
 * <p>Instances are immutable. Use {@link #withTypeMap(Map)} (and the other {@code with*} methods) to
 * derive a context with a different per-call setting.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public final class PgCodecContext extends CodecContext {

  /**
   * PostgreSQL's {@code FirstNormalObjectId}: OIDs below this are built-in catalog objects; at or
   * above it are user- or extension-defined.
   */
  private static final int FIRST_NORMAL_OBJECT_ID = 16384;

  private final @Nullable BaseConnection connection;
  private final @Nullable TypeInfo typeInfo;
  private final @Nullable CodecRegistry codecs;
  private final @Nullable JavaTypeRegistry javaTypes;
  // Offline (connectionless) type source: resolveType/resolveCodec consult it when typeInfo is null.
  // Empty for a connection-bound context, which resolves child types through typeInfo instead.
  private final Map<Integer, TypeDescriptor> typesByOid;
  private final Map<String, Class<?>> typeMap;
  private final @Nullable Encoding encoding;
  private final Charset charset;
  private final @Nullable TimestampUtils timestampUtils;

  // Zone of the Calendar threaded by getDate/getTime/getTimestamp(col, Calendar) via
  // withCalendar(). Null means "use the connection default" (matching getObject, which supplies no
  // Calendar). Only the zone is kept: it is all the temporal decoding reads, and it keeps a caller's
  // mutable Calendar from being retained or published.
  private final @Nullable TimeZone callerTimeZone;

  // Date/time type preferences (from connection properties)
  private final PrefersJavaTime prefersJavaTime;

  // When true, getInt/Long/Float/Double/BigDecimal on a BOOL column converts
  // 't'/'f' (or binary 0/1) to 1/0 instead of throwing.
  private final boolean convertBooleanToNumeric;

  // IntervalStyle for a connectionless context. A connection-bound one reads the reported server
  // parameter instead, so this is unused there.
  private final IntervalStyle offlineIntervalStyle;

  /**
   * Creates a new PgCodecContext from a connection with default preferences.
   *
   * @param connection the database connection
   * @param codecs the codec registry
   * @param javaTypes the Java type registry
   * @throws SQLException if the encoding cannot be retrieved
   */
  public PgCodecContext(BaseConnection connection, CodecRegistry codecs,
      JavaTypeRegistry javaTypes) throws SQLException {
    this(connection, codecs, javaTypes, Collections.emptyMap(), PrefersJavaTime.NONE, false);
  }

  /**
   * Creates a new PgCodecContext with a specific type map and date/time preferences.
   *
   * @param connection the database connection
   * @param codecs the codec registry
   * @param javaTypes the Java type registry
   * @param typeMap the type map for custom mappings
   * @param prefersJavaTime the per-type getObject java.time preferences
   * @param convertBooleanToNumeric true if numeric getters on a BOOL column convert 't'/'f' to 1/0
   * @throws SQLException if the encoding cannot be retrieved
   */
  public PgCodecContext(BaseConnection connection, CodecRegistry codecs,
      JavaTypeRegistry javaTypes, Map<String, Class<?>> typeMap,
      PrefersJavaTime prefersJavaTime,
      boolean convertBooleanToNumeric) throws SQLException {
    this.connection = connection;
    this.typeInfo = connection.getTypeInfo();
    this.codecs = codecs;
    this.javaTypes = javaTypes;
    this.typesByOid = Collections.emptyMap();
    this.typeMap = typeMap == null || typeMap.isEmpty()
        ? Collections.emptyMap()
        : Collections.unmodifiableMap(
            IdentifierNormalizingTypeMap.of(typeMap, this.typeInfo));
    this.encoding = connection.getEncoding();
    this.charset = Charset.forName(encoding.name());
    this.timestampUtils = null;
    this.callerTimeZone = null;
    this.prefersJavaTime = prefersJavaTime;
    this.convertBooleanToNumeric = convertBooleanToNumeric;
    this.offlineIntervalStyle = IntervalStyle.POSTGRES;
  }

  /**
   * Package-private constructor for unit testing without a database connection.
   *
   * @param timestampUtils the timestamp utilities
   * @param charset the character set
   * @param prefersJavaTime the per-type getObject java.time preferences
   */
  PgCodecContext(TimestampUtils timestampUtils, Charset charset,
      PrefersJavaTime prefersJavaTime) {
    this(timestampUtils, charset, prefersJavaTime, false);
  }

  /**
   * Package-private constructor for unit testing without a database connection,
   * allowing the {@code convertBooleanToNumeric} flag to be configured.
   */
  PgCodecContext(TimestampUtils timestampUtils, Charset charset,
      PrefersJavaTime prefersJavaTime,
      boolean convertBooleanToNumeric) {
    this.connection = null;
    this.typeInfo = null;
    this.codecs = null;
    this.javaTypes = null;
    this.typesByOid = Collections.emptyMap();
    this.typeMap = Collections.emptyMap();
    this.encoding = null;
    this.charset = charset;
    this.timestampUtils = timestampUtils;
    this.callerTimeZone = null;
    this.prefersJavaTime = prefersJavaTime;
    this.convertBooleanToNumeric = convertBooleanToNumeric;
    this.offlineIntervalStyle = IntervalStyle.POSTGRES;
  }

  /**
   * Constructs a connectionless context for offline encoding and decoding. The wire settings come
   * from {@code timestampUtils} and {@code charset} rather than a live connection; {@code codecs}
   * resolves codecs by OID and {@code typesByOid} resolves child type descriptors. Built through
   * {@link OfflineBuilder}.
   */
  private PgCodecContext(TimestampUtils timestampUtils, Charset charset,
      CodecRegistry codecs, Map<Integer, TypeDescriptor> typesByOid,
      PrefersJavaTime prefersJavaTime,
      boolean convertBooleanToNumeric,
      IntervalStyle offlineIntervalStyle) {
    this.connection = null;
    this.typeInfo = null;
    this.codecs = codecs;
    this.javaTypes = null;
    this.typesByOid = typesByOid;
    this.typeMap = Collections.emptyMap();
    // Derive a wire Encoding from the charset so the encoding readers (such as hstore) work offline;
    // getEncoding() would otherwise be null without a connection.
    this.encoding = Encoding.getJVMEncoding(charset.name());
    this.charset = charset;
    this.timestampUtils = timestampUtils;
    this.callerTimeZone = null;
    this.prefersJavaTime = prefersJavaTime;
    this.convertBooleanToNumeric = convertBooleanToNumeric;
    this.offlineIntervalStyle = offlineIntervalStyle;
  }

  /**
   * Returns a builder for a connectionless {@link CodecContext} that encodes and decodes offline.
   *
   * <p>Supply the wire settings (charset, time zone, integer-datetime mode), the
   * {@link CodecRegistry} that resolves codecs, and descriptors for any child types a container
   * would resolve. The result drives {@link org.postgresql.api.codec.Codecs#encode} and
   * {@link org.postgresql.api.codec.Codecs#decode} for scalar and temporal types with no
   * connection.</p>
   *
   * @return a new offline builder
   */
  static CodecContextBuilder offlineBuilder() {
    return new OfflineBuilder();
  }

  /**
   * Returns a fresh codec registry with the built-in codecs, viewed through the read-only
   * {@link CodecLookup} SPI. Package-private: {@link OfflineCodecs#defaultRegistry()} is the public
   * entry point.
   *
   * @return a new default codec registry
   */
  static CodecLookup newDefaultRegistry() {
    return new CodecRegistry();
  }

  /**
   * Returns a new PgCodecContext with the specified type map.
   *
   * <p>This is used for operations that accept a type map parameter,
   * such as {@code getArray(Map)} or {@code getObject(int, Map)}.</p>
   *
   * @param typeMap the new type map
   * @return a new PgCodecContext with the specified type map
   * @throws SQLException if the encoding cannot be retrieved
   */
  public PgCodecContext withTypeMap(Map<String, Class<?>> typeMap) throws SQLException {
    if (typeMap == null || typeMap.isEmpty()) {
      if (this.typeMap.isEmpty()) {
        return this;
      }
      typeMap = Collections.emptyMap();
    }
    // withTypeMap is only meaningful on a connection-backed context; the
    // test-only constructor produces a context with a null connection / null
    // registries. Reject calls on such a context rather than synthesizing a
    // partially-constructed copy.
    BaseConnection conn = connection;
    CodecRegistry registries = codecs;
    JavaTypeRegistry javaTypeReg = javaTypes;
    if (conn == null || registries == null || javaTypeReg == null) {
      throw Exceptions.withTypeMapNotSupportedConnectionless();
    }
    PgCodecContext copy = new PgCodecContext(conn, registries, javaTypeReg, typeMap,
        prefersJavaTime, convertBooleanToNumeric);
    if (timestampUtils != null) {
      copy = copy.withTimestampUtils(timestampUtils);
    }
    return copy;
  }

  /**
   * Returns a new PgCodecContext that uses the given TimestampUtils instance
   * for date/time conversions. This is meant for callers like PgResultSet
   * that maintain a per-instance TimestampUtils (so timezone caching is
   * scoped to the result set rather than shared at the connection level).
   *
   * @param utils the TimestampUtils to use, or null to fall through to the
   *     connection-level default
   * @return a new PgCodecContext bound to {@code utils} for {@code usesIntegerDateTimes()} and
   *     {@code getClientTimeZone()}
   */
  @SuppressWarnings("ReferenceEquality")
  public PgCodecContext withTimestampUtils(@Nullable TimestampUtils utils) {
    if (utils == this.timestampUtils) {
      return this;
    }
    return new PgCodecContext(this, utils);
  }

  /**
   * Copy constructor with a custom TimestampUtils.
   */
  private PgCodecContext(PgCodecContext source, @Nullable TimestampUtils utils) {
    this.connection = source.connection;
    this.typeInfo = source.typeInfo;
    this.codecs = source.codecs;
    this.javaTypes = source.javaTypes;
    this.typesByOid = source.typesByOid;
    this.typeMap = source.typeMap;
    this.encoding = source.encoding;
    this.charset = source.charset;
    this.timestampUtils = utils;
    this.callerTimeZone = source.callerTimeZone;
    this.prefersJavaTime = source.prefersJavaTime;
    this.convertBooleanToNumeric = source.convertBooleanToNumeric;
    this.offlineIntervalStyle = source.offlineIntervalStyle;
  }

  /**
   * Returns a new PgCodecContext carrying the supplied {@link Calendar} for the next
   * decode. {@code getDate/getTime/getTimestamp(col, Calendar)} use this to thread the
   * caller's Calendar to the temporal codecs without changing the codec method
   * signatures. The Calendar is borrowed, not copied: it is consumed synchronously
   * within a single decode, so the codecs stay stateless.
   *
   * @param cal the Calendar to use, or null for the connection default
   * @return a context reporting {@code cal}'s zone from {@link #getCallerTimeZone()}
   */
  public PgCodecContext withCalendar(@Nullable Calendar cal) {
    TimeZone tz = cal == null ? null : cal.getTimeZone();
    if (tz == null ? callerTimeZone == null : tz.equals(callerTimeZone)) {
      return this;
    }
    return new PgCodecContext(this, cal);
  }

  /**
   * Returns a context with all {@code getObject} java.time preferences cleared, so the temporal
   * codecs yield {@code java.sql.Date}/{@code Time}/{@code Timestamp} rather than
   * {@code LocalDate}/{@code LocalTime}/… Used when decoding temporal <em>array</em> elements:
   * {@code getArray()} returns the SQL temporal types regardless of the per-getObject preferences,
   * matching the legacy array decoder. Returns {@code this} when no preference is set.
   *
   * @return a context that decodes temporal values as the {@code java.sql} types
   */
  @Override
  public PgCodecContext withoutJavaTimePreferences() {
    if (PrefersJavaTime.NONE.equals(prefersJavaTime)) {
      return this;
    }
    return new PgCodecContext(this);
  }

  /**
   * Copy constructor that clears the java.time {@code getObject} preferences.
   */
  private PgCodecContext(PgCodecContext source) {
    this.connection = source.connection;
    this.typeInfo = source.typeInfo;
    this.codecs = source.codecs;
    this.javaTypes = source.javaTypes;
    this.typesByOid = source.typesByOid;
    this.typeMap = source.typeMap;
    this.encoding = source.encoding;
    this.charset = source.charset;
    this.timestampUtils = source.timestampUtils;
    this.callerTimeZone = source.callerTimeZone;
    this.prefersJavaTime = PrefersJavaTime.NONE;
    this.convertBooleanToNumeric = source.convertBooleanToNumeric;
    this.offlineIntervalStyle = source.offlineIntervalStyle;
  }

  /**
   * Copy constructor with a per-call Calendar.
   */
  private PgCodecContext(PgCodecContext source, @Nullable Calendar cal) {
    this.connection = source.connection;
    this.typeInfo = source.typeInfo;
    this.codecs = source.codecs;
    this.javaTypes = source.javaTypes;
    this.typesByOid = source.typesByOid;
    this.typeMap = source.typeMap;
    this.encoding = source.encoding;
    this.charset = source.charset;
    this.timestampUtils = source.timestampUtils;
    this.callerTimeZone = cal == null ? null : cal.getTimeZone();
    this.prefersJavaTime = source.prefersJavaTime;
    this.convertBooleanToNumeric = source.convertBooleanToNumeric;
    this.offlineIntervalStyle = source.offlineIntervalStyle;
  }

  /**
   * Returns the underlying database connection.
   *
   * <p>Note: Prefer using specific accessors like {@link #getTypeInfo()},
   * {@link #getEncoding()}, etc. Direct connection access should be limited
   * to operations not available through PgCodecContext.</p>
   *
   * @return the database connection
   */
  public BaseConnection getConnection() {
    return castNonNull(connection,
        "PgCodecContext has no connection (constructed for unit testing only)");
  }

  /**
   * Returns the live connection, or fails with a clear message when this context is connectionless.
   *
   * <p>The container codecs build a connection-bound {@link PgArray} / {@link PgStruct} and call this
   * so an offline context reports the limitation instead of dereferencing a null connection (which
   * {@link #getConnection()} would do, since {@code castNonNull} is a no-op without assertions).
   * Offline encode and decode currently covers scalar and temporal types; materialising a container
   * value still needs a connection.</p>
   *
   * @param type the type being decoded, named in the error
   * @return the live connection
   * @throws SQLException if this context has no connection
   */
  public BaseConnection requireConnection(TypeDescriptor type) throws SQLException {
    BaseConnection conn = connection;
    if (conn == null) {
      throw Exceptions.cannotDecodeOffline(type.getFormattedName());
    }
    return conn;
  }

  @Override
  public CodecValueFactory getValueFactory() {
    return new ValueFactory();
  }

  /**
   * Builds the driver's {@link PgStruct}/{@link PgArray} and {@code SQLData} adapters for a codec,
   * so the built-in codecs construct them without downcasting the descriptor to {@link PgType} or
   * this context to {@link PgCodecContext}.
   *
   * <p>Every method narrows the descriptor here rather than at each codec: a {@code TypeDescriptor}
   * reaching a built-in container codec is the driver's own {@link PgType}, and this class is the
   * one place that may rely on it.</p>
   */
  private final class ValueFactory implements CodecValueFactory {

    @Override
    public Struct createStruct(TypeDescriptor type, @Nullable Object[] attributes,
        @Nullable CharSequence literal) throws SQLException {
      PgStruct struct = PgStruct.withCodecContext(pgType(type), attributes, PgCodecContext.this);
      // PgStruct is also a PGobject; recording the literal keeps the legacy getValue() contract
      // exact instead of rebuilding the text from the attributes. Only an existing String is kept:
      // the struct outlives this call, so a borrowed slice would have to be copied, and paying that
      // for a text view the caller may never read is not worth it.
      if (literal instanceof String) {
        struct.setValue((String) literal);
      }
      return struct;
    }

    @Override
    public @Nullable Array createArray(TypeDescriptor type, byte[] data) throws SQLException {
      // Null, not an error: an offline caller decodes the payload eagerly instead. Only an explicit
      // java.sql.Array target needs the connection, and that path reports it through
      // requireConnection.
      return connection == null
          ? null
          : new PgArray(connection, type.getOid(), type.getAppliedTypmod(), data);
    }

    @Override
    public @Nullable Array createArray(TypeDescriptor type, CharSequence literal)
        throws SQLException {
      // toString() only once a connection backs the array: PgArray decodes lazily and so must own
      // its literal, while the connectionless case copies nothing and lets the caller decode the
      // borrowed view in place.
      return connection == null
          ? null
          : new PgArray(connection, type.getOid(), type.getAppliedTypmod(), literal.toString());
    }

    @Override
    public SQLInput createSQLInput(TypeDescriptor type, byte[] data, int offset, int length)
        throws SQLException {
      return new PgSQLInputBinary(data, offset, length, pgType(type), PgCodecContext.this);
    }

    @Override
    public SQLInput createSQLInput(TypeDescriptor type, CharSequence literal)
        throws SQLException {
      return new PgSQLInputText(literal, pgType(type), PgCodecContext.this);
    }

    @Override
    public void writeSQLData(TypeDescriptor type, SQLData value, BackpatchingByteArrayOutputStream sink)
        throws SQLException {
      write(value, new PgSQLOutputBinary(pgType(type), PgCodecContext.this, sink));
    }

    @Override
    public void writeSQLData(TypeDescriptor type, SQLData value, Appendable out)
        throws SQLException {
      write(value, new PgSQLOutputText(pgType(type), PgCodecContext.this, out));
    }

    /**
     * Drives {@code writeSQL} and then closes the output, which finishes the composite framing:
     * the length back-patch in binary, the closing parenthesis in text.
     */
    private void write(SQLData value, PgSQLOutput out) throws SQLException {
      try (PgSQLOutput output = out) {
        value.writeSQL(output);
      }
    }

    private PgType pgType(TypeDescriptor type) {
      return (PgType) type;
    }
  }

  /**
   * Returns whether this context is bound to a live connection, and therefore has
   * a {@link TypeInfo} and {@link CodecRegistry}. The unit-testing constructor
   * produces a context that is not connection-bound; callers that need the
   * registry or type cache must fall back when this returns {@code false}.
   *
   * @return true if connection-bound
   */
  public boolean isConnectionBound() {
    return connection != null;
  }

  /**
   * Returns the type information cache.
   *
   * @return the type info cache
   */
  public TypeInfo getTypeInfo() {
    return castNonNull(typeInfo,
        "PgCodecContext has no TypeInfo (constructed for unit testing only)");
  }

  /**
   * Returns the codec registry.
   *
   * @return the codec registry
   */
  public CodecRegistry getCodecs() {
    return castNonNull(codecs,
        "PgCodecContext has no CodecRegistry (constructed for unit testing only)");
  }

  /**
   * Returns the Java type registry.
   *
   * @return the Java type registry
   */
  public JavaTypeRegistry getJavaTypes() {
    return castNonNull(javaTypes,
        "PgCodecContext has no JavaTypeRegistry (constructed for unit testing only)");
  }

  /**
   * Resolves a child type by OID, loading the lazily-cached structure the container codecs read off
   * the descriptor: composite attributes ({@code pg_attribute}), the range subtype
   * ({@code pg_range.rngsubtype}), and the multirange's range type ({@code pg_range.rngtypid}) —
   * none of which {@code pg_type.typelem} carries. Other types resolve to the plain
   * {@link TypeInfo#getPgTypeByOid(int)} lookup.
   */
  @Override
  public TypeDescriptor resolveType(int oid) throws SQLException {
    TypeInfo ti = typeInfo;
    if (ti != null) {
      return ti.resolveFully(oid);
    }
    // Offline: consult the caller-supplied map first, then the driver's built-in type catalog (so
    // built-in scalar, temporal and array OIDs resolve without registration), then fail clearly.
    TypeDescriptor offline = typesByOid.get(oid);
    if (offline != null) {
      return offline;
    }
    PgType builtin = TypeInfoCache.getDefaultType(oid);
    if (builtin != null) {
      return builtin;
    }
    throw Exceptions.noOfflineTypeDescriptor(oid);
  }

  /**
   * Resolves the codec for a child type by OID. The lookup dispatches by type name and, failing
   * that, by {@code typtype}/{@code typcategory}; it does not need the descriptor's structure, so it
   * uses the plain {@link TypeInfo#getPgTypeByOid(int)} lookup rather than {@link #resolveType(int)}.
   */
  @Override
  public Codec resolveCodec(int oid) throws SQLException {
    TypeInfo ti = typeInfo;
    if (ti != null) {
      return getCodecs().getByOid(oid, ti.getPgTypeByOid(oid));
    }
    CodecRegistry registry = codecs;
    if (registry == null) {
      throw Exceptions.noCodecRegistry(oid);
    }
    // Offline: pass the caller-supplied descriptor, falling back to the built-in catalog, so the
    // registry can dispatch a container codec by typtype/typcategory for a built-in array/composite.
    TypeDescriptor pgType = typesByOid.get(oid);
    if (pgType == null) {
      pgType = TypeInfoCache.getDefaultType(oid);
    }
    return registry.getByOid(oid, pgType);
  }

  /**
   * Returns the connection's character encoding.
   *
   * @return the character encoding
   */
  public Encoding getEncoding() {
    return castNonNull(encoding,
        "PgCodecContext has no Encoding (constructed for unit testing only)");
  }

  /**
   * Returns the connection's character set.
   *
   * @return the character set
   */
  @Override
  public Charset getCharset() {
    return charset;
  }

  /**
   * Returns the {@code IntervalStyle} the interval codec renders a binary {@code interval} with, so
   * it matches what the server would print in text mode.
   *
   * <p>Connection-bound, this is the reported GUC_REPORT parameter status. Offline there is no
   * server to ask, so it is the value given to
   * {@link org.postgresql.api.codec.CodecContextBuilder#intervalStyle(IntervalStyle)}, which
   * defaults to {@link IntervalStyle#POSTGRES}.</p>
   *
   * @return the current interval style, never null
   */
  @Override
  public IntervalStyle getIntervalStyle() {
    BaseConnection c = connection;
    if (c == null) {
      return offlineIntervalStyle;
    }
    return IntervalStyle.fromServerValue(c.getParameterStatus("IntervalStyle"));
  }

  /**
   * Returns whether the backend uses 64-bit integers (rather than doubles) for time values. Temporal
   * codecs read this to decode binary {@code time}/{@code timestamp} payloads.
   *
   * @return true if the backend uses integer datetimes
   */
  @Override
  public boolean usesIntegerDateTimes() {
    TimestampUtils tu = timestampUtils;
    if (tu != null) {
      return !tu.usesDouble();
    }
    return getConnection().getQueryExecutor().getIntegerDateTimes();
  }

  /**
   * Returns the JVM default time zone used to decode/encode {@code date}/{@code time}/
   * {@code timestamp} (without time zone) when no per-call {@link Calendar} is supplied.
   *
   * @return the default time zone
   */
  @Override
  public TimeZone getDefaultTimeZone() {
    return TimeZone.getDefault();
  }

  /**
   * Returns the client/session time zone (the backend's {@code TimeZone} setting). Temporal codecs
   * use it to render binary {@code timetz}/{@code timestamptz} values as text the same way text
   * mode does.
   *
   * @return the client time zone
   */
  @Override
  public TimeZone getClientTimeZone() {
    TimestampUtils tu = timestampUtils;
    if (tu != null) {
      return tu.getClientTimeZone();
    }
    return castNonNull(getConnection().getQueryExecutor().getTimeZone(),
        "Backend timezone is not known");
  }

  /**
   * Returns the zone of the Calendar set via {@link #withCalendar(Calendar)}, or null when none was
   * supplied (the connection default applies). Temporal codecs read this to honour the
   * {@code Calendar} passed to {@code getDate/getTime/getTimestamp(col, Calendar)}.
   *
   * @return the caller-supplied time zone, or null
   */
  @Override
  public @Nullable TimeZone getCallerTimeZone() {
    return callerTimeZone;
  }

  /**
   * Returns the class the JDBC connection type map ({@link java.sql.Connection#setTypeMap}) assigns
   * to {@code type}, consulting the fully qualified name first and then the bare name.
   *
   * <p>Returns {@code null} when there is no entry, and always for a built-in type
   * (oid &lt; {@code FirstNormalObjectId}): the JDBC type map customizes only user-defined types, so
   * a stale or mistaken entry such as {@code {"varchar" -> Foo}} cannot hijack a built-in column.
   * The pgjdbc {@code addDataType} registry is not consulted here; see
   * {@link #getRegisteredClass(String)}.</p>
   *
   * @param type the type to look up
   * @return the mapped class, or {@code null} for no entry or a built-in type
   */
  @Nullable Class<?> getTypeMapClass(TypeDescriptor type) {
    if (type.getOid() < FIRST_NORMAL_OBJECT_ID) {
      return null;
    }
    Class<?> mapped = typeMap.get(type.getFormattedName());
    if (mapped == null) {
      mapped = typeMap.get(type.getName().getLocalName());
    }
    return mapped;
  }

  /**
   * Returns the class registered for {@code typeName} through {@code addDataType} (or the default
   * {@link JavaTypeRegistry} mapping), independent of the JDBC connection type map. Applies to
   * built-in and user-defined types alike.
   *
   * @param typeName the PostgreSQL type name
   * @return the registered class, or {@code null} if none
   */
  @Nullable Class<?> getRegisteredClass(String typeName) {
    JavaTypeRegistry javaTypeReg = javaTypes;
    return javaTypeReg == null ? null : javaTypeReg.getPGobject(typeName);
  }

  /**
   * Returns the per-type java.time preferences {@code getObject} decodes temporal values with.
   *
   * <p>Set by the {@code getObject*} connection properties, or by
   * {@link CodecContextBuilder#prefersJavaTime(PrefersJavaTime)} offline.</p>
   *
   * @return the java.time preferences, never null
   */
  @Override
  public PrefersJavaTime getJavaTimePreferences() {
    return prefersJavaTime;
  }

  /**
   * Returns whether numeric getters on a BOOL column should convert {@code 't'}/{@code 'f'}
   * (or binary {@code 0}/{@code 1}) to {@code 1}/{@code 0} instead of throwing.
   *
   * <p>Controlled by the {@code convertBooleanToNumeric} connection property.</p>
   *
   * @return true if BOOL→numeric conversion is enabled
   */
  @Override
  public boolean convertsBooleanToNumeric() {
    return convertBooleanToNumeric;
  }

  /**
   * Builds a connectionless {@link CodecContext} for offline encoding and decoding. Obtain one from
   * {@link PgCodecContext#offlineBuilder()}.
   *
   * <p>Defaults: UTF-8, UTC, integer datetimes, a fresh {@link CodecRegistry} with the built-in
   * codecs, no {@code getObject} java.time preferences, and no boolean-to-numeric coercion.</p>
   */
  @Experimental("Codec API is experimental and may change in future releases")
  public static final class OfflineBuilder implements CodecContextBuilder {
    private Charset charset = StandardCharsets.UTF_8;
    private TimeZone timeZone = TimeZone.getTimeZone("UTC");
    private boolean integerDateTimes = true;
    private @Nullable CodecRegistry registry;
    private final Map<Integer, TypeDescriptor> typesByOid = new HashMap<>();
    private PrefersJavaTime prefersJavaTime = PrefersJavaTime.NONE;
    private boolean convertBooleanToNumeric;
    private IntervalStyle intervalStyle = IntervalStyle.POSTGRES;

    private OfflineBuilder() {
    }

    /**
     * Sets the character set for text values. Defaults to UTF-8.
     *
     * @param charset the character set
     * @return this builder
     */
    @Override
    public OfflineBuilder charset(Charset charset) {
      this.charset = charset;
      return this;
    }

    /**
     * Sets the client/session time zone temporal codecs render {@code timetz}/{@code timestamptz}
     * against. Defaults to UTC.
     *
     * @param clientTimeZone the session time zone
     * @return this builder
     */
    @Override
    public OfflineBuilder clientTimeZone(TimeZone clientTimeZone) {
      this.timeZone = clientTimeZone;
      return this;
    }

    /**
     * Sets whether the backend encodes binary {@code time}/{@code timestamp} payloads as 64-bit
     * integers ({@code true}, the modern default) rather than doubles.
     *
     * @param integerDateTimes true for integer datetimes
     * @return this builder
     */
    @Override
    public OfflineBuilder integerDateTimes(boolean integerDateTimes) {
      this.integerDateTimes = integerDateTimes;
      return this;
    }

    /**
     * Sets the codec registry that resolves codecs by OID and name. Defaults to a fresh
     * {@link CodecRegistry} with the built-in codecs.
     *
     * @param registry the codec registry
     * @return this builder
     */
    @Override
    public OfflineBuilder registry(CodecLookup registry) {
      if (!(registry instanceof CodecRegistry)) {
        throw new IllegalArgumentException(
            "registry must be obtained from OfflineCodecs.defaultRegistry(); got "
                + (registry == null ? "null" : registry.getClass().getName()));
      }
      this.registry = (CodecRegistry) registry;
      return this;
    }

    /**
     * Registers {@code type} under its own OID so a container can resolve it as a child type.
     *
     * @param type the type descriptor
     * @return this builder
     */
    @Override
    public OfflineBuilder type(TypeDescriptor type) {
      this.typesByOid.put(type.getOid(), type);
      return this;
    }

    /**
     * Registers every descriptor in {@code types}, keyed by OID.
     *
     * @param types the type descriptors by OID
     * @return this builder
     */
    @Override
    public OfflineBuilder types(Map<Integer, ? extends TypeDescriptor> types) {
      this.typesByOid.putAll(types);
      return this;
    }

    /**
     * Sets the {@code getObject} java.time preferences, matching the per-type connection properties.
     * Each flag makes {@code decode(..., Object.class)} on that type yield the java.time class rather
     * than the {@code java.sql} one.
     *
     * @param prefers the per-type java.time preferences; build one with {@link PrefersJavaTime#builder()}
     * @return this builder
     */
    @Override
    public OfflineBuilder prefersJavaTime(PrefersJavaTime prefers) {
      this.prefersJavaTime = prefers;
      return this;
    }

    /**
     * Sets whether numeric getters on a {@code bool} value coerce it to {@code 1}/{@code 0} instead
     * of throwing.
     *
     * @param convertBooleanToNumeric true to enable the coercion
     * @return this builder
     */
    @Override
    public OfflineBuilder convertBooleanToNumeric(boolean convertBooleanToNumeric) {
      this.convertBooleanToNumeric = convertBooleanToNumeric;
      return this;
    }

    /**
     * Sets the {@code IntervalStyle} the interval codec renders a binary {@code interval} with.
     * Defaults to {@link IntervalStyle#POSTGRES}.
     *
     * @param intervalStyle the interval style
     * @return this builder
     */
    @Override
    public OfflineBuilder intervalStyle(IntervalStyle intervalStyle) {
      this.intervalStyle = intervalStyle;
      return this;
    }

    /**
     * Builds the connectionless context.
     *
     * @return a {@link CodecContext} that encodes and decodes without a connection
     */
    @Override
    public CodecContext build() {
      CodecRegistry codecs = registry != null ? registry : new CodecRegistry();
      TimeZone tz = timeZone;
      TimestampUtils timestampUtils = new TimestampUtils(!integerDateTimes, () -> tz);
      Map<Integer, TypeDescriptor> types = typesByOid.isEmpty()
          ? Collections.<Integer, TypeDescriptor>emptyMap()
          : Collections.unmodifiableMap(new HashMap<>(typesByOid));
      return new PgCodecContext(timestampUtils, charset, codecs, types,
          prefersJavaTime, convertBooleanToNumeric, intervalStyle);
    }
  }
}
