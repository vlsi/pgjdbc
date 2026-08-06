/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.api.Experimental;
import org.postgresql.api.codec.CompositeAttribute;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.api.codec.TypeName;
import org.postgresql.core.Oid;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Represents a PostgreSQL type.
 *
 * <p>Instances are immutable; {@link #withAttributes}, {@link #withTypmod},
 * {@link #withRangeSubtype} and {@link #withMultirangeRange} each return a copy. Those copies are
 * how lazily-loaded catalog structure arrives: a composite's attributes, a range's subtype and a
 * multirange's range type stay absent until {@link org.postgresql.core.TypeInfo} loads them, and an
 * absent one reads the same as "this kind of type has none" — {@link Oid#UNSPECIFIED}, or an empty
 * attribute list. {@link #hasFieldsLoaded()} is the only member that separates the two.</p>
 *
 * @since 42.8.0
 */
@Experimental("PgType API is experimental and may change in future releases")
public class PgType implements TypeDescriptor {
  // pg_type.typsend names whose binary wire is the raw charset text, so a codec-less type using one
  // decodes the same in binary and text (see hasTextLikeSend()).
  private static final Set<String> TEXT_LIKE_SENDS = Collections.unmodifiableSet(
      new HashSet<>(Arrays.asList("textsend", "varcharsend", "bpcharsend", "namesend")));

  private final TypeName typeName;
  private final String fullName;
  private final int oid;
  private final char typtype;      // 'b'=base, 'c'=composite, 'e'=enum, 'd'=domain, 'p'=pseudo, 'r'=range, 'm'=multirange
  private final char typcategory;  // 'A'=array, 'B'=boolean, 'N'=numeric, 'S'=string, etc.
  private final int typtypmod;
  // The modifier applied to the value this descriptor stands for (a column's typmod, a composite
  // attribute's modifier, or a domain's pinned modifier); -1 unless stamped via withTypmod(). This is
  // distinct from typtypmod, which is the type's own pg_type.typtypmod.
  private final int typmod;

  private final int typelem;
  private final int arrayOid;
  private final int typbasetype;   // base type OID for domains
  private final char delimiter;
  // pg_type.typsend function name; "-" means no binary-send function exists
  private final String typsend;
  // pg_type.typreceive function name; "-" means no binary-receive function exists
  private final String typreceive;

  // Composite type fields; null both for a non-composite type and for a composite whose fields are
  // not loaded yet. hasFieldsLoaded() tells the two apart; getAttributes() reports either as empty.
  private final @Nullable List<PgField> fields;

  // Range subtype OID (pg_range.rngsubtype); 0 for non-range types or until loaded.
  // Ranges carry typelem == 0, so the subtype lives here rather than in typelem.
  private final int rangeSubtype;

  // Multirange's range type OID (pg_range.rngtypid, joined on rngmultitypid); 0 for non-multirange
  // types or until loaded. A multirange's element is a range, so the link is to the range type
  // rather than to a scalar subtype.
  private final int multirangeRange;

  /**
   * Constructs a new PgType for non-composite types.
   *
   * @param typeName the type name
   * @param fullName the full name of the type
   * @param oid the OID of the type
   * @param typtype the type type ('b'=base, 'c'=composite, 'e'=enum, 'd'=domain, etc.)
   * @param typcategory the type category ('A'=array, 'B'=boolean, 'N'=numeric, 'S'=string, etc.)
   * @param typtypmod the type modifier
   * @param typelem for array types, the OID of the element type
   * @param arrayOid for non-array types, the OID of the corresponding array type
   * @param typbasetype for domain types, the OID of the base type
   */
  public PgType(TypeName typeName, String fullName, int oid, char typtype, char typcategory,
                int typtypmod, int typelem, int arrayOid, int typbasetype) {
    this(typeName, fullName, oid, typtype, typcategory, typtypmod, typelem, arrayOid, typbasetype,
        oid == Oid.BOX || oid == Oid.BOX_ARRAY ? ';' : ',', null, "-", "-");
  }

  /**
   * Constructs a new PgType for non-composite types, carrying the {@code pg_type.typsend}
   * and {@code pg_type.typreceive} function names.  Pass {@code "-"} when the type has no
   * binary-send or binary-receive function.
   *
   * <p>The parameter order mirrors {@code TypeInfoCache.BASE_TYPES} and the generator in
   * {@code TypeInfoCacheTest.generateBaseTypes}: {@code typsend} and {@code typreceive} sit
   * right after {@code typtypmod}.</p>
   *
   * @param typeName the type name
   * @param fullName the full name of the type
   * @param oid the OID of the type
   * @param typtype the type type ('b'=base, 'c'=composite, 'e'=enum, 'd'=domain, etc.)
   * @param typcategory the type category ('A'=array, 'B'=boolean, 'N'=numeric, 'S'=string, etc.)
   * @param typtypmod the type modifier
   * @param typsend the name of the binary-send function from pg_type.typsend, or {@code "-"}
   * @param typreceive the name of the binary-receive function from pg_type.typreceive, or {@code "-"}
   * @param typelem for array types, the OID of the element type
   * @param arrayOid for non-array types, the OID of the corresponding array type
   * @param typbasetype for domain types, the OID of the base type
   */
  public PgType(TypeName typeName, String fullName, int oid, char typtype, char typcategory,
                int typtypmod, String typsend, String typreceive, int typelem, int arrayOid,
                int typbasetype) {
    this(typeName, fullName, oid, typtype, typcategory, typtypmod, typelem, arrayOid, typbasetype,
        oid == Oid.BOX || oid == Oid.BOX_ARRAY ? ';' : ',', null, typsend, typreceive);
  }

  /**
   * Constructs a new PgType with delimiter from database.
   *
   * @param typeName the type name
   * @param fullName the full name of the type
   * @param oid the OID of the type
   * @param typtype the type type ('b'=base, 'c'=composite, 'e'=enum, 'd'=domain, etc.)
   * @param typcategory the type category ('A'=array, 'B'=boolean, 'N'=numeric, 'S'=string, etc.)
   * @param typtypmod the type modifier
   * @param typelem for array types, the OID of the element type
   * @param arrayOid for non-array types, the OID of the corresponding array type
   * @param typbasetype for domain types, the OID of the base type
   * @param delimiter the array delimiter character from pg_type.typdelim
   */
  public PgType(TypeName typeName, String fullName, int oid, char typtype, char typcategory,
                int typtypmod, int typelem, int arrayOid, int typbasetype, char delimiter) {
    this(typeName, fullName, oid, typtype, typcategory, typtypmod, typelem, arrayOid, typbasetype, delimiter, null, "-", "-");
  }

  /**
   * Constructs a new PgType with delimiter, {@code pg_type.typsend} and {@code pg_type.typreceive}
   * from the database.
   *
   * @param typeName the type name
   * @param fullName the full name of the type
   * @param oid the OID of the type
   * @param typtype the type type ('b'=base, 'c'=composite, 'e'=enum, 'd'=domain, etc.)
   * @param typcategory the type category ('A'=array, 'B'=boolean, 'N'=numeric, 'S'=string, etc.)
   * @param typtypmod the type modifier
   * @param typelem for array types, the OID of the element type
   * @param arrayOid for non-array types, the OID of the corresponding array type
   * @param typbasetype for domain types, the OID of the base type
   * @param delimiter the array delimiter character from pg_type.typdelim
   * @param typsend the name of the binary-send function from pg_type.typsend, or {@code "-"}
   * @param typreceive the name of the binary-receive function from pg_type.typreceive, or {@code "-"}
   */
  public PgType(TypeName typeName, String fullName, int oid, char typtype, char typcategory,
                int typtypmod, int typelem, int arrayOid, int typbasetype, char delimiter,
                String typsend, String typreceive) {
    this(typeName, fullName, oid, typtype, typcategory, typtypmod, typelem, arrayOid, typbasetype, delimiter, null, typsend, typreceive);
  }

  /**
   * Constructs a new PgType with composite type fields.
   *
   * @param typeName the type name
   * @param fullName the full name of the type
   * @param oid the OID of the type
   * @param typtype the type type ('b'=base, 'c'=composite, 'e'=enum, 'd'=domain, etc.)
   * @param typcategory the type category ('A'=array, 'B'=boolean, 'N'=numeric, 'S'=string, etc.)
   * @param typtypmod the type modifier
   * @param typelem for array types, the OID of the element type
   * @param arrayOid for non-array types, the OID of the corresponding array type
   * @param typbasetype for domain types, the OID of the base type
   * @param fields the list of fields for composite types, or null for non-composite types
   */
  public PgType(TypeName typeName, String fullName, int oid, char typtype, char typcategory,
                int typtypmod, int typelem, int arrayOid, int typbasetype,
                @Nullable List<PgField> fields) {
    this(typeName, fullName, oid, typtype, typcategory, typtypmod, typelem, arrayOid, typbasetype,
        oid == Oid.BOX || oid == Oid.BOX_ARRAY ? ';' : ',', fields, "-", "-");
  }

  /**
   * Constructs a new PgType with all parameters.
   *
   * @param typeName the type name
   * @param fullName the full name of the type
   * @param oid the OID of the type
   * @param typtype the type type ('b'=base, 'c'=composite, 'e'=enum, 'd'=domain, etc.)
   * @param typcategory the type category ('A'=array, 'B'=boolean, 'N'=numeric, 'S'=string, etc.)
   * @param typtypmod the type modifier
   * @param typelem for array types, the OID of the element type
   * @param arrayOid for non-array types, the OID of the corresponding array type
   * @param typbasetype for domain types, the OID of the base type
   * @param delimiter the array delimiter character from pg_type.typdelim
   * @param fields the list of fields for composite types, or null for non-composite types
   */
  public PgType(TypeName typeName, String fullName, int oid, char typtype, char typcategory,
                int typtypmod, int typelem, int arrayOid, int typbasetype, char delimiter,
                @Nullable List<PgField> fields) {
    this(typeName, fullName, oid, typtype, typcategory, typtypmod, typelem, arrayOid, typbasetype,
        delimiter, fields, "-", "-");
  }

  private PgType(TypeName typeName, String fullName, int oid, char typtype, char typcategory,
                 int typtypmod, int typelem, int arrayOid, int typbasetype, char delimiter,
                 @Nullable List<PgField> fields, String typsend, String typreceive) {
    this(typeName, fullName, oid, typtype, typcategory, typtypmod, typelem, arrayOid, typbasetype,
        delimiter, fields, typsend, typreceive, Oid.UNSPECIFIED, Oid.UNSPECIFIED, -1);
  }

  private PgType(TypeName typeName, String fullName, int oid, char typtype, char typcategory,
                 int typtypmod, int typelem, int arrayOid, int typbasetype, char delimiter,
                 @Nullable List<PgField> fields, String typsend, String typreceive,
                 int rangeSubtype, int multirangeRange, int typmod) {
    this.typeName = typeName;
    this.fullName = fullName;
    this.oid = oid;
    this.typtype = typtype;
    this.typcategory = typcategory;
    this.typtypmod = typtypmod;
    this.typelem = typelem;
    this.arrayOid = arrayOid;
    this.typbasetype = typbasetype;
    this.delimiter = delimiter;
    this.typsend = typsend;
    this.typreceive = typreceive;
    this.fields = fields != null ? Collections.unmodifiableList(fields) : null;
    this.rangeSubtype = rangeSubtype;
    this.multirangeRange = multirangeRange;
    this.typmod = typmod;
  }

  /**
   * Returns the JDBC SQL type for the given PostgreSQL type characteristics.
   * For built-in types, uses OID for precise mapping. For user-defined types,
   * falls back to typcategory/typtype.
   *
   * @param oid the type OID
   * @param typcategory the type category
   * @param typtype the type type
   * @return the JDBC SQL type constant from {@link java.sql.Types}
   */
  public static int toJdbcSqlType(int oid, char typcategory, char typtype) {
    // Domains are JDBC DISTINCT regardless of base typcategory (per JDBC spec).
    // This precedence matters for callers like PgDatabaseMetaData.getColumns,
    // where the jdbc3 contract reports DATA_TYPE=DISTINCT for domain columns
    // while still keeping the base type's COLUMN_SIZE / DECIMAL_DIGITS.
    if (typtype == 'd') {
      return Types.DISTINCT;
    }
    // For built-in types, use OID for precise mapping
    int sqlType = getSqlTypeByOid(oid);
    if (sqlType != Types.OTHER) {
      return sqlType;
    }
    // Fall back to typcategory/typtype for user-defined types
    return toJdbcSqlType(typcategory, typtype);
  }

  /**
   * Returns the JDBC SQL type based on typcategory and typtype only.
   * Used for user-defined types where OID is not known in advance.
   */
  public static int toJdbcSqlType(char typcategory, char typtype) {
    // Domain precedence over the inherited typcategory (see toJdbcSqlType(oid, ...)).
    if (typtype == 'd') {
      return Types.DISTINCT;
    }
    switch (typcategory) {
      case 'A':
        return Types.ARRAY;
      case 'B':
        return Types.BOOLEAN; // Boolean category for user-defined types
      case 'D':
        return Types.TIMESTAMP;
      case 'N':
        return Types.NUMERIC;
      case 'S':
        return Types.VARCHAR;
      default:
        break;
    }
    switch (typtype) {
      case 'c':
        return Types.STRUCT;
      case 'e':
        return Types.VARCHAR;
      default:
        break;
    }
    return Types.OTHER;
  }

  /**
   * Returns the JDBC SQL type for well-known PostgreSQL OIDs.
   * Returns Types.OTHER if the OID is not a known built-in type.
   */
  private static int getSqlTypeByOid(int oid) {
    switch (oid) {
      // Numeric types
      case Oid.INT2:
        return Types.SMALLINT;
      case Oid.INT4:
        return Types.INTEGER;
      case Oid.INT8:
        return Types.BIGINT;
      case Oid.OID:
        return Types.BIGINT;
      case Oid.FLOAT4:
        return Types.REAL;
      case Oid.FLOAT8:
        return Types.DOUBLE;
      case Oid.MONEY:
        return Types.DOUBLE;
      case Oid.NUMERIC:
        return Types.NUMERIC;
      // Boolean - returns Types.BIT for backward compatibility.
      // Types.BOOLEAN would be more semantically correct, but changing this
      // would break existing applications that rely on the current behavior.
      // See: https://github.com/pgjdbc/pgjdbc/issues/3230
      // See: https://github.com/pgjdbc/pgjdbc/pull/895
      // Use connection property map.pg_type.boolean=boolean to get Types.BOOLEAN
      case Oid.BOOL:
        return Types.BIT;
      // Bit strings
      case Oid.BIT:
        return Types.BIT;
      case Oid.VARBIT:
        return Types.OTHER;
      // String types
      case Oid.VARCHAR:
        return Types.VARCHAR;
      case Oid.TEXT:
        return Types.VARCHAR;
      case Oid.BPCHAR:
        return Types.CHAR;
      case Oid.NAME:
        return Types.VARCHAR;
      // Binary
      case Oid.BYTEA:
        return Types.BINARY;
      // Date/Time
      case Oid.DATE:
        return Types.DATE;
      case Oid.TIME:
      case Oid.TIMETZ:
        return Types.TIME;
      case Oid.TIMESTAMP:
      case Oid.TIMESTAMPTZ:
        // Legacy pgjdbc reports both timestamp and timestamptz as
        // Types.TIMESTAMP via DatabaseMetaData / ResultSetMetaData; switching
        // timestamptz to Types.TIMESTAMP_WITH_TIMEZONE (2014) breaks
        // downstream code (Hibernate / jOOQ type switches, our own
        // DatabaseMetaDataTest, etc.) that pattern-match on Types.TIMESTAMP.
        return Types.TIMESTAMP;
      // Special types
      case Oid.REFCURSOR:
        return Types.REF_CURSOR;
      case Oid.XML:
        return Types.SQLXML;
      default:
        return Types.OTHER;
    }
  }

  /**
   * Gets the OID of the type.
   *
   * @return the OID
   */
  @Override
  public int getOid() {
    return oid;
  }

  @Override
  public int getCatalogTypmod() {
    return typtypmod;
  }

  @Override
  public int getAppliedTypmod() {
    return typmod;
  }

  /**
   * Gets the typtype value.
   *
   * @return the typtype ('b'=base, 'c'=composite, 'e'=enum, 'd'=domain, 'p'=pseudo, 'r'=range, 'm'=multirange)
   */
  @Override
  public char getTyptype() {
    return typtype;
  }

  /**
   * Gets the typcategory value.
   *
   * @return the typcategory ('A'=array, 'B'=boolean, 'N'=numeric, 'S'=string, etc.)
   */
  @Override
  public char getTypcategory() {
    return typcategory;
  }

  /**
   * Gets the base type OID for domain types.
   *
   * @return the base type OID, or 0 if this is not a domain type
   */
  @Override
  public int getTypbasetype() {
    return typbasetype;
  }

  /**
   * Gets the SQL type of the type, computed from OID and typcategory/typtype.
   * For built-in types, uses OID for precise mapping. For user-defined types,
   * falls back to typcategory/typtype.
   *
   * @return the SQL type (a constant from {@link java.sql.Types})
   */
  public int getSqlType() {
    return toJdbcSqlType(oid, typcategory, typtype);
  }

  /**
   * Gets the OID of the element type for array types.
   *
   * @return the element type OID, or Oid.UNSPECIFIED if this is not an array type
   */
  @Override
  public int getTypelem() {
    return typelem;
  }

  /**
   * Gets the OID of the corresponding array type for non-array types.
   *
   * @return the array type OID, or Oid.UNSPECIFIED if there is no corresponding array type
   */
  @Override
  public int getArrayOid() {
    return arrayOid;
  }

  /**
   * Gets the range subtype OID ({@code pg_range.rngsubtype}) for a range type.
   *
   * <p>{@link #getTypelem()} is {@code 0} for ranges, so the element the range is over
   * (for example {@code int4} for {@code int4range}) is carried here instead. The subtype
   * lives in {@code pg_catalog.pg_range} and is loaded lazily, so this returns
   * {@link Oid#UNSPECIFIED} for a non-range type or a range whose subtype has not been
   * loaded yet; call {@link org.postgresql.core.TypeInfo#getRangeSubtype(int)} to force the
   * load.</p>
   *
   * @return the range subtype OID, or {@link Oid#UNSPECIFIED} if not a range or not yet loaded
   */
  @Override
  public int getRangeSubtype() {
    return rangeSubtype;
  }

  /**
   * Gets the range type OID ({@code pg_range.rngtypid}, joined on {@code rngmultitypid}) for a
   * multirange type.
   *
   * <p>A multirange's element is a range rather than a scalar, so the companion range type (for
   * example {@code int4range} for {@code int4multirange}) is carried here. It lives in
   * {@code pg_catalog.pg_range} and is loaded lazily, so this returns {@link Oid#UNSPECIFIED} for a
   * non-multirange type or a multirange whose range has not been loaded yet; call
   * {@link org.postgresql.core.TypeInfo#getMultirangeRange(int)} to force the load.</p>
   *
   * @return the range type OID, or {@link Oid#UNSPECIFIED} if not a multirange or not yet loaded
   */
  @Override
  public int getMultirangeRange() {
    return multirangeRange;
  }

  /**
   * Gets the delimiter used in array string representations.
   *
   * @return the delimiter character
   */
  @Override
  public char getDelimiter() {
    return delimiter;
  }

  /**
   * Gets the {@code pg_type.typsend} function name.
   * Returns {@code "-"} when no binary-send function exists for this type.
   *
   * @return the typsend function name, or {@code "-"}
   */
  public String getTypsend() {
    return typsend;
  }

  /**
   * Gets the {@code pg_type.typreceive} function name.
   * Returns {@code "-"} when no binary-receive function exists for this type.
   *
   * @return the typreceive function name, or {@code "-"}
   */
  public String getTypreceive() {
    return typreceive;
  }

  /**
   * Reports whether this type itself has a binary-send function in the catalog,
   * i.e. {@code pg_type.typsend} is not {@code "-"}.
   *
   * <p>This is the non-recursive, leaf-level check. For containers (arrays,
   * composites, domains) a present {@code typsend} (such as {@code array_send})
   * does not guarantee the contents can be sent in binary — use
   * {@link org.postgresql.core.TypeInfo#backendCanSendBinary(PgType)}, which recurses
   * into element, field and base types.</p>
   *
   * @return true if the type has its own binary-send function
   */
  public boolean hasOwnBinarySend() {
    return !"-".equals(typsend);
  }

  /**
   * Reports whether this type itself has a binary-receive function in the catalog,
   * i.e. {@code pg_type.typreceive} is not {@code "-"}.
   *
   * <p>This is the non-recursive, leaf-level check, mirroring {@link #hasOwnBinarySend()}.</p>
   *
   * @return true if the type has its own binary-receive function
   */
  public boolean hasOwnBinaryReceive() {
    return !"-".equals(typreceive);
  }

  /**
   * Reports whether this type's binary-send function emits raw charset text, i.e.
   * {@code pg_type.typsend} is one of the built-in text sends
   * ({@code textsend}/{@code varcharsend}/{@code bpcharsend}/{@code namesend}).
   *
   * <p>For such a type the binary wire bytes are the charset text, so a codec-less value decodes the
   * same in binary and text — letting {@code TextLikeCodec} return a readable {@code PGobject} even
   * for a value nested in a binary {@code record}. The match is by exact name: it recognises the
   * built-in raw-text sends and does not attempt to prove function-OID identity, so a same-named
   * function in another schema is intentionally not treated as text-like.</p>
   *
   * @return true if the type's {@code typsend} is a built-in raw-text send
   */
  public boolean hasTextLikeSend() {
    return TEXT_LIKE_SENDS.contains(typsend);
  }

  /**
   * Gets the name of the type.
   *
   * @return the type name
   */
  @Override
  public TypeName getName() {
    return typeName;
  }

  /**
   * Gets the full name of the type.
   *
   * @return the full name
   */
  @Override
  public String getFormattedName() {
    return fullName;
  }

  /**
   * Returns whether this is a composite type (struct).
   *
   * @return true if this is a composite type (typtype='c')
   */
  @Override
  public boolean isComposite() {
    return typtype == 'c';
  }

  /**
   * Returns whether this is a multirange type.
   *
   * @return true if this is a multirange type (typtype='m')
   */
  @Override
  public boolean isMultirange() {
    return typtype == 'm';
  }

  /**
   * Returns whether this is a domain type.
   *
   * @return true if this is a domain type (typtype='d')
   */
  @Override
  public boolean isDomain() {
    return typtype == 'd';
  }

  /**
   * Returns whether this is an enum type.
   *
   * @return true if this is an enum type (typtype='e')
   */
  @Override
  public boolean isEnum() {
    return typtype == 'e';
  }

  /**
   * Returns whether this is an array type.
   *
   * @return true if this is an array type (typcategory='A')
   */
  @Override
  public boolean isArray() {
    return typcategory == 'A';
  }

  /**
   * Gets the attributes of a composite type, or an empty list when this type has none.
   *
   * <p>A composite whose fields the driver has not loaded yet also reports an empty list, because
   * the public contract has no "not loaded" state; {@link #hasFieldsLoaded()} tells the two
   * apart.</p>
   *
   * @return the list of attributes, empty when the type has none or they are not loaded
   */
  @Override
  public List<PgField> getAttributes() {
    List<PgField> loaded = fields;
    return loaded == null ? Collections.emptyList() : loaded;
  }

  /**
   * Returns whether this type has its fields loaded.
   * Only meaningful for composite types.
   *
   * @return true if this is a composite type and its fields have been loaded
   */
  public boolean hasFieldsLoaded() {
    return fields != null;
  }

  /**
   * Creates a copy of this PgType with the specified attributes, used to update a composite type
   * after loading them.
   *
   * <p>The driver builds {@link PgField} attributes, so the common case passes them straight
   * through; a foreign {@link CompositeAttribute} (an offline descriptor registered through the
   * codec builder) is copied into one, numbering positions from the list order since
   * {@code CompositeAttribute} does not carry a position.</p>
   *
   * @param attributes the list of attributes
   * @return a new PgType with the attributes set
   */
  @Override
  public PgType withAttributes(List<? extends CompositeAttribute> attributes) {
    List<PgField> converted = new ArrayList<>(attributes.size());
    int position = 1;
    for (CompositeAttribute attribute : attributes) {
      converted.add(attribute instanceof PgField
          ? (PgField) attribute
          : new PgField(attribute.getName(), attribute.getTypeOid(), position,
              attribute.getAppliedTypmod(), attribute.getType()));
      position++;
    }
    return new PgType(typeName, fullName, oid, typtype, typcategory,
        typtypmod, typelem, arrayOid, typbasetype, delimiter, converted, typsend, typreceive,
        rangeSubtype, multirangeRange, typmod);
  }

  /**
   * Creates a copy of this PgType reporting the given applied modifier from {@link #getAppliedTypmod()}.
   * Used to stamp a result column's, composite attribute's, or domain's modifier onto the descriptor
   * handed to a codec, so a modifier-sensitive type such as {@code numeric(10,2)} decodes correctly.
   * Leaves {@code pg_type.typtypmod} ({@link #getCatalogTypmod()}) unchanged.
   *
   * @param typmod the applied type modifier, or {@code -1} for none
   * @return a copy reporting {@code typmod} from {@link #getAppliedTypmod()}, or {@code this} if unchanged
   */
  @Override
  public PgType withTypmod(int typmod) {
    if (typmod == this.typmod) {
      return this;
    }
    return new PgType(typeName, fullName, oid, typtype, typcategory,
        typtypmod, typelem, arrayOid, typbasetype, delimiter, fields, typsend, typreceive,
        rangeSubtype, multirangeRange, typmod);
  }

  /**
   * Creates a copy of this PgType carrying the given range subtype OID.
   * Used to enrich a range type after loading {@code pg_range.rngsubtype}.
   *
   * @param rangeSubtype the range subtype OID from {@code pg_range}
   * @return a new PgType with the range subtype set
   */
  public PgType withRangeSubtype(int rangeSubtype) {
    return new PgType(typeName, fullName, oid, typtype, typcategory,
        typtypmod, typelem, arrayOid, typbasetype, delimiter, fields, typsend, typreceive,
        rangeSubtype, multirangeRange, typmod);
  }

  /**
   * Creates a copy of this PgType carrying the given multirange range type OID.
   * Used to enrich a multirange type after loading {@code pg_range.rngtypid}.
   *
   * @param multirangeRange the range type OID from {@code pg_range}
   * @return a new PgType with the multirange range type set
   */
  public PgType withMultirangeRange(int multirangeRange) {
    return new PgType(typeName, fullName, oid, typtype, typcategory,
        typtypmod, typelem, arrayOid, typbasetype, delimiter, fields, typsend, typreceive,
        rangeSubtype, multirangeRange, typmod);
  }

  /**
   * Reports whether values of this type are case-sensitive.
   *
   * @return the same as {@link #isCaseSensitive(int)} for this type's OID
   */
  public boolean isCaseSensitive() {
    return isCaseSensitive(oid);
  }

  /**
   * Reports whether values of the given type are case-sensitive.
   *
   * @param oid the type's OID
   * @return false for the built-in numeric, boolean, bit and date/time types; true for every other
   *     OID, including one the driver does not recognise
   */
  public static boolean isCaseSensitive(int oid) {
    switch (oid) {
      case Oid.OID:
      case Oid.INT2:
      case Oid.INT4:
      case Oid.INT8:
      case Oid.FLOAT4:
      case Oid.FLOAT8:
      case Oid.NUMERIC:
      case Oid.BOOL:
      case Oid.BIT:
      case Oid.VARBIT:
      case Oid.DATE:
      case Oid.TIME:
      case Oid.TIMETZ:
      case Oid.TIMESTAMP:
      case Oid.TIMESTAMPTZ:
      case Oid.INTERVAL:
        return false;
      default:
        return true;
    }
  }

  /**
   * Reports whether values of this type carry a sign.
   *
   * @return the same as {@link #isSigned(int)} for this type's OID
   */
  public boolean isSigned() {
    return isSigned(oid);
  }

  /**
   * Reports whether values of the given type carry a sign.
   *
   * @param oid the type's OID
   * @return true only for the built-in signed numeric types; false for every other OID, including
   *     one the driver does not recognise
   */
  public static boolean isSigned(int oid) {
    switch (oid) {
      case Oid.INT2:
      case Oid.INT4:
      case Oid.INT8:
      case Oid.FLOAT4:
      case Oid.FLOAT8:
      case Oid.NUMERIC:
        return true;
      default:
        return false;
    }
  }

  /**
   * Reports whether a literal of this type has to be quoted in SQL.
   *
   * @return the same as {@link #requiresQuotingSqlType(int)} for {@link #getSqlType()}
   */
  public boolean requiresQuoting() {
    return requiresQuotingSqlType(getSqlType());
  }

  /**
   * Reports whether a literal of the given JDBC type has to be quoted in SQL.
   *
   * @param sqlType a constant from {@link java.sql.Types}
   * @return false for the numeric type codes, whose literals are self-delimiting; true for every
   *     other code, so an unrecognised type is quoted rather than pasted in raw
   */
  public static boolean requiresQuotingSqlType(int sqlType) {
    switch (sqlType) {
      case Types.BIGINT:
      case Types.DOUBLE:
      case Types.FLOAT:
      case Types.INTEGER:
      case Types.REAL:
      case Types.SMALLINT:
      case Types.TINYINT:
      case Types.NUMERIC:
      case Types.DECIMAL:
        return false;
      default:
        return true;
    }
  }
}
