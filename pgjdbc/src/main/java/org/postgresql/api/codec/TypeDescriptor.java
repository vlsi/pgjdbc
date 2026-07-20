/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import java.util.List;

/**
 * Read-only catalog metadata a {@link Codec} reads about the type it handles.
 *
 * <p>This is the type half of the codec contract: every {@code decode}/{@code encode}
 * method receives a {@code TypeDescriptor} describing the PostgreSQL type of the value.
 * It exposes the {@code pg_type} (and, for ranges, {@code pg_range}) columns the codec
 * layer needs — OID, name, modifiers, element/array/base/subtype OIDs, composite attributes,
 * and the {@code typtype}/{@code typcategory} discriminators — without tying a codec to
 * the driver's internal type class. The driver's own type implements this interface.</p>
 *
 * <p>Each accessor named after a catalog column reports that column's value exactly as PostgreSQL
 * stores it. The driver does not normalize, reinterpret, or extend these values, so the PostgreSQL
 * documentation for the named column is the specification — including the cases where a column
 * means less than its name suggests (see {@link #getTypelem()}). The {@code isXxx()} predicates are
 * conveniences derived from them, not a separate classification.</p>
 *
 * <p>A descriptor handed to a codec is resolved for its own kind: a composite carries its
 * attributes, a range its subtype, a multirange its range type. So {@code 0} from
 * {@link #getTypelem()}, {@link #getTypbasetype()}, {@link #getRangeSubtype()} or
 * {@link #getMultirangeRange()}, and an empty {@link #getAttributes()}, mean only "does not apply
 * to this type" — never "not loaded yet". A codec reads them without re-resolving, and metadata
 * that cannot be loaded surfaces as an error rather than as a zero.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public interface TypeDescriptor {

  /**
   * Returns the type OID.
   *
   * @return the type OID
   */
  int getOid();

  /**
   * Returns the catalog identity of the type: {@code pg_type.typname} plus the schema it lives in
   * ({@code pg_namespace.nspname}).
   *
   * <p>This is the name the type is registered and looked up under. It is not always what a user
   * would write in SQL — {@code bool} here is {@code boolean} in {@link #getFormattedName()}, and an
   * array's {@code typname} carries the leading underscore ({@code _int4}).</p>
   *
   * @return the catalog type name
   */
  TypeName getName();

  /**
   * Returns the type as the server renders it for display: exactly
   * {@code pg_catalog.format_type(oid, null)}.
   *
   * <p>This is the SQL-standard spelling, so it differs from {@link #getName()} for the types that
   * have one: {@code bool} renders as {@code boolean}, {@code _int4} as {@code integer[]}. Use it in
   * messages a user reads; use {@link #getName()} to identify the type.</p>
   *
   * @return the formatted type name
   */
  String getFormattedName();

  /**
   * Returns {@code pg_type.typtypmod}: the modifier the type itself pins, which is set for a domain
   * declared over a modifier-carrying base type ({@code CREATE DOMAIN price AS numeric(10,2)}) and
   * {@code -1} otherwise.
   *
   * <p>A base type carries {@code -1} here even where a column pins a modifier; that one reaches a
   * codec through {@link #getAppliedTypmod()}.</p>
   *
   * @return the type's own modifier, or {@code -1} when it pins none
   */
  int getCatalogTypmod();

  /**
   * Returns the modifier applied to the value at this position: a result column's type modifier, a
   * composite attribute's modifier, or a domain's pinned modifier.
   *
   * <p>This differs from {@link #getCatalogTypmod()}, which reports the type's own
   * {@code pg_type.typtypmod} from the catalog. A base type such as {@code numeric} carries
   * {@code typtypmod == -1} even when a column pins a precision and scale, so the applied modifier of
   * {@code numeric(10,2)} reaches a codec only through this method, not through
   * {@link #getCatalogTypmod()}.</p>
   *
   * <p>A codec reads this when the decode depends on the modifier — for example, rescaling a
   * {@code numeric} to the column's declared scale. The default is {@code -1}, meaning no modifier
   * applies.</p>
   *
   * @return the applied type modifier, or {@code -1} when none applies
   */
  default int getAppliedTypmod() {
    return -1;
  }

  /**
   * Returns a view of this descriptor that reports {@code typmod} from {@link #getAppliedTypmod()} and
   * leaves every other property unchanged.
   *
   * <p>The driver stamps a result column's modifier onto the descriptor it hands a codec, so the
   * codec can decode a modifier-sensitive type. An offline caller does the same to decode a value as,
   * say, {@code numeric(10,2)}: {@code ctx.resolveType(oid).withTypmod(typmod)}.</p>
   *
   * @param typmod the modifier to report from {@link #getAppliedTypmod()}
   * @return a descriptor equal to this one except for {@link #getAppliedTypmod()}
   */
  default TypeDescriptor withTypmod(int typmod) {
    return new TypmodTypeDescriptor(this, typmod);
  }

  /**
   * Returns {@code pg_type.typelem}: the element type when this type has one.
   *
   * <p>Non-zero does <em>not</em> mean "array". PostgreSQL sets {@code typelem} on several
   * non-array types too — {@code point} and {@code line} report {@code float8}, {@code name}
   * reports {@code "char"}. Test for an array with {@link #isArray()}, which reads
   * {@code typcategory}.</p>
   *
   * @return the element type OID, or {@code 0} when the type has no element type
   */
  int getTypelem();

  /**
   * Returns {@code pg_type.typarray}: the array type whose element is this type.
   *
   * @return the array type OID, or {@code 0} when no array type exists for this one
   */
  int getArrayOid();

  /**
   * Returns {@code pg_type.typbasetype}: the type a domain is built on.
   *
   * @return the base type OID, or {@code 0} when this is not a domain
   */
  int getTypbasetype();

  /**
   * Returns {@code pg_range.rngsubtype}: the type a range is over.
   *
   * <p>{@link #getTypelem()} is {@code 0} for ranges, so the element the range is over
   * (for example {@code int4} for {@code int4range}) is carried here instead.</p>
   *
   * @return the range subtype OID, or {@code 0} if not a range
   */
  int getRangeSubtype();

  /**
   * Returns {@code pg_range.rngtypid} of the row whose {@code rngmultitypid} is this type: the range
   * type a multirange is over.
   *
   * <p>A multirange ({@code typtype='m'}) carries its elements as ranges rather than scalars, so the
   * companion range type (for example {@code int4range} for {@code int4multirange}) is carried here.
   * Resolve that range with {@link CodecContext#resolveType(int)} to reach its subtype in turn.</p>
   *
   * @return the range type OID, or {@code 0} if not a multirange
   */
  int getMultirangeRange();

  /**
   * Returns {@code pg_type.typtype} as the catalog holds it: {@code 'b'} base, {@code 'c'}
   * composite, {@code 'd'} domain, {@code 'e'} enum, {@code 'm'} multirange, {@code 'p'} pseudo,
   * {@code 'r'} range.
   *
   * <p>The listing above is what PostgreSQL defines today; a future release may add a value, and
   * this method will report it unchanged. Treat an unrecognized character as an unknown kind rather
   * than assuming the set is closed.</p>
   *
   * @return the {@code typtype} character
   */
  char getTyptype();

  /**
   * Returns {@code pg_type.typcategory} as the catalog holds it ({@code 'A'} array, {@code 'B'}
   * boolean, {@code 'N'} numeric, {@code 'S'} string, and the rest of PostgreSQL's category codes,
   * including the {@code 'U'} user-defined catch-all).
   *
   * <p>As with {@link #getTyptype()}, the set is PostgreSQL's and may grow.</p>
   *
   * @return the {@code typcategory} character
   */
  char getTypcategory();

  /**
   * Returns {@code pg_type.typdelim}: the character separating elements in an array literal of this
   * type. A comma for nearly every type, a semicolon for {@code box}.
   *
   * @return the delimiter character
   */
  char getDelimiter();

  /**
   * Returns the attributes of a composite type, or an empty list for a type that is not composite.
   *
   * <p>A descriptor reaching a codec is resolved for its kind, so a composite carries its
   * attributes here. An empty list therefore means "this type has no attributes", never "the
   * attributes are not loaded yet" — test the kind with {@link #isComposite()} rather than reading
   * emptiness as a kind check. The anonymous {@code record} pseudo-type is the one composite that
   * reports an empty list, because the catalog carries no attributes for it; a codec reading one
   * synthesizes them from the self-describing binary wire and stamps them on with
   * {@link #withAttributes(List)}.</p>
   *
   * @return the composite attributes, empty when the type has none
   */
  List<? extends CompositeAttribute> getAttributes();

  /**
   * Returns a view of this descriptor reporting {@code attributes} from {@link #getAttributes()},
   * leaving every other property unchanged.
   *
   * <p>This exists for the anonymous {@code record} pseudo-type, whose attributes the catalog does
   * not carry: the binary wire is self-describing, so a codec reading one synthesizes the
   * attributes from the wire and stamps them onto the descriptor.</p>
   *
   * @param attributes the attributes to report
   * @return a descriptor equal to this one except for {@link #getAttributes()}
   */
  TypeDescriptor withAttributes(List<? extends CompositeAttribute> attributes);

  /**
   * Reports whether {@link #getTypcategory()} is {@code 'A'}.
   *
   * <p>That is PostgreSQL's array category, which also covers the fixed-length vector types
   * ({@code oidvector}, {@code int2vector}) — they are array-like and the driver decodes them
   * through the array path.</p>
   *
   * @return true for an array-category type
   */
  default boolean isArray() {
    return getTypcategory() == 'A';
  }

  /**
   * Reports whether {@link #getTyptype()} is {@code 'd'}, PostgreSQL's domain kind.
   *
   * @return true for a domain type
   */
  default boolean isDomain() {
    return getTyptype() == 'd';
  }

  /**
   * Reports whether {@link #getTyptype()} is {@code 'e'}, PostgreSQL's enum kind.
   *
   * @return true for an enum type
   */
  default boolean isEnum() {
    return getTyptype() == 'e';
  }

  /**
   * Reports whether {@link #getTyptype()} is {@code 'c'}, PostgreSQL's composite kind.
   *
   * @return true for a composite type
   */
  default boolean isComposite() {
    return getTyptype() == 'c';
  }

  /**
   * Reports whether {@link #getTyptype()} is {@code 'm'}, PostgreSQL's multirange kind.
   *
   * @return true for a multirange type
   */
  default boolean isMultirange() {
    return getTyptype() == 'm';
  }
}
