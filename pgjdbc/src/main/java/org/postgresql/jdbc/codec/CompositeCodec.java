/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingByteArrayOutputStream;
import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.CodecFormatSupport;
import org.postgresql.api.codec.CompositeAttribute;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.StreamingTextCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.CodecDepth;
import org.postgresql.jdbc.PgField;
import org.postgresql.jdbc.PgSQLOutputText;
import org.postgresql.jdbc.PgStruct;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PGobject;
import org.postgresql.util.internal.CompositeWireErrors;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Codec for PostgreSQL composite (record) types.
 *
 * <p>This codec handles encoding and decoding of PostgreSQL composite types,
 * including SQLData implementations.</p>
 *
 * <p>Note: Composite type handling requires type metadata which is typically
 * retrieved from TypeInfoCache. Full composite support is handled via
 * PgStruct and SQLInput/SQLOutput implementations.</p>
 */
public final class CompositeCodec implements StreamingBinaryCodec, StreamingTextCodec {

  public static final CompositeCodec INSTANCE = new CompositeCodec();

  private CompositeCodec() {
    // Singleton for composite handling
  }

  /**
   * Returns the composite's attribute fields, loading them through the context when the descriptor
   * does not already carry them. The anonymous RECORD pseudo-type has no catalog attributes, so its
   * fields resolve to an empty list.
   */
  private static List<? extends CompositeAttribute> resolveFields(
      TypeDescriptor type, CodecContext ctx) throws SQLException {
    List<? extends CompositeAttribute> fields = type.getAttributes();
    if (!fields.isEmpty()) {
      return fields;
    }
    // Empty means either the anonymous RECORD, which has no catalog attributes, or a descriptor
    // whose attributes the driver has not loaded. Re-resolving settles both: RECORD stays empty.
    return ctx.resolveType(type.getOid()).getAttributes();
  }

  /**
   * Returns the attribute's type. A connection-bound composite resolved its attribute types when
   * its metadata was loaded, so the descriptor comes off the attribute; an offline attribute has
   * none and is resolved through the context, stamped with its modifier.
   */
  private static TypeDescriptor attributeType(CompositeAttribute field, CodecContext ctx)
      throws SQLException {
    TypeDescriptor cached = field.getType();
    return cached != null
        ? cached
        : ctx.resolveType(field.getTypeOid(), field.getAppliedTypmod());
  }

  // =========================================================================
  // Static utility methods for binary composite encoding/decoding
  // =========================================================================

  /**
   * Represents a decoded field from a composite type.
   */
  public static final class DecodedField {
    private final int typeOid;
    private final byte[] source;
    private final int offset;
    private final int length;

    DecodedField(int typeOid, byte[] source, int offset, int length) {
      this.typeOid = typeOid;
      this.source = source;
      this.offset = offset;
      this.length = length;
    }

    /**
     * Returns the OID of the field's type.
     */
    public int getTypeOid() {
      return typeOid;
    }

    /**
     * Returns a copy of this field's raw binary data, or null if the field is NULL.
     */
    public byte @Nullable [] getData() {
      return length < 0 ? null : Arrays.copyOfRange(source, offset, offset + length);
    }

    /**
     * Returns true if this field is NULL.
     */
    public boolean isNull() {
      return length < 0;
    }

    /**
     * Decodes this field through {@code codec}, reading directly from the backing
     * buffer without copying the field's bytes out first.
     */
    @Nullable Object decode(BinaryCodec codec, TypeDescriptor type, CodecContext ctx) throws SQLException {
      return codec.decodeBinary(source, offset, length, type, ctx);
    }
  }

  /**
   * Decodes a composite type from binary format.
   *
   * <p>Binary format for composite types:</p>
   * <pre>
   * [4 bytes] field_count (int32)
   * For each field:
   *   [4 bytes] field_type_oid (int32)
   *   [4 bytes] field_length (-1 for NULL, otherwise length in bytes)
   *   [N bytes] field_data (if length &gt; 0)
   * </pre>
   *
   * @param data the binary data
   * @return list of decoded fields
   * @throws SQLException if the data format is invalid
   */
  public static List<DecodedField> decodeBinaryFields(byte[] data) throws SQLException {
    return decodeBinaryFields(data, 0, data.length);
  }

  /**
   * Decodes the composite fields contained in {@code data[start, start + len)}.
   *
   * <p>Each {@link DecodedField} records the field's {@code (offset, length)}
   * within {@code data} instead of a copied {@code byte[]}, so callers that
   * decode through a codec avoid a slice allocation per field.
   * {@link DecodedField#getData()} still materializes a copy on demand.</p>
   *
   * @param data the backing buffer
   * @param start start of the composite within {@code data}
   * @param len length of the composite
   * @return list of decoded fields referencing {@code data}
   * @throws SQLException if the data format is invalid
   */
  public static List<DecodedField> decodeBinaryFields(byte[] data, int start, int len)
      throws SQLException {
    if (len < 4) {
      throw CompositeWireErrors.tooShort();
    }

    int end = start + len;
    int pos = start;

    int fieldCount = ByteConverter.int4(data, pos);
    pos += 4;
    if (fieldCount < 0) {
      throw CompositeWireErrors.negativeFieldCount(fieldCount);
    }
    // Bound the field count against the bytes that remain before sizing the list: every field carries
    // at least an 8-byte header (type OID + length) on the wire, so a count larger than
    // (remaining bytes / 8) is corrupt. Without this, a hostile count near Integer.MAX_VALUE would
    // drive an OutOfMemoryError in the ArrayList allocation before the per-field bounds check runs.
    if (fieldCount > (end - pos) / 8) {
      throw CompositeWireErrors.fieldCountExceedsData(fieldCount);
    }

    List<DecodedField> fields = new ArrayList<>(fieldCount);

    for (int i = 0; i < fieldCount; i++) {
      if (end - pos < 8) {
        throw CompositeWireErrors.unexpectedEnd(i);
      }

      int typeOid = ByteConverter.int4(data, pos);
      pos += 4;
      int length = ByteConverter.int4(data, pos);
      pos += 4;

      if (length == -1) {
        fields.add(new DecodedField(typeOid, data, pos, -1));
      } else if (length < 0) {
        throw CompositeWireErrors.fieldLength(length, i);
      } else {
        if (end - pos < length) {
          throw CompositeWireErrors.notEnoughData(i);
        }
        fields.add(new DecodedField(typeOid, data, pos, length));
        pos += length;
      }
    }

    return fields;
  }

  /**
   * Checks if a value needs quoting in composite text format.
   *
   * @param value the value to check
   * @return true if the value contains characters that require quoting
   */
  public static boolean needsQuoting(String value) {
    if (value.isEmpty()) {
      return true;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == ',' || c == '(' || c == ')' || c == '"' || c == '\\'
          || Character.isWhitespace(c)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Encodes struct/composite attributes as a PostgreSQL composite text value
   * by delegating each attribute to its per-field {@link TextCodec}.
   *
   * <p>Attribute types come from {@code compositeType.getAttributes()}, which a descriptor
   * reaching a codec already carries. Each non-null attribute is converted by the registered
   * text codec for its attribute's OID, then quoted/escaped per PostgreSQL's
   * composite text format.</p>
   *
   * @param attributes the attribute values (may contain nulls)
   * @param compositeType the composite type metadata, resolved for its kind
   * @param ctx the codec context
   * @return the text representation in PostgreSQL composite format: (val1,val2,...)
   * @throws SQLException if a field codec is missing or attribute encoding fails
   */
  public static String encodeAttributesAsText(
      @Nullable Object[] attributes,
      TypeDescriptor compositeType,
      CodecContext ctx) throws SQLException {
    StringBuilder sb = new StringBuilder();
    encodeAttributes(attributes, compositeType, ctx, sb);
    return sb.toString();
  }

  /** {@link Appendable} overload used by the streaming text path. */
  private static void appendQuotedField(Appendable out, String value) throws IOException {
    if (!needsQuoting(value)) {
      out.append(value);
      return;
    }
    ContainerTextEscaper.appendQuotedRecordStyle(out, value);
  }

  /**
   * Reads each field of a PostgreSQL composite text literal {@code (f0,f1,...)}
   * from {@code cur} and hands it to {@code consumer} as a borrowed,
   * already-unquoted slice. The surrounding parentheses are optional.
   *
   * <p>A field is SQL NULL iff it was unquoted and empty; a quoted empty field
   * is the empty string. Whitespace is part of the field, as it is for
   * {@code record_in}, so {@code ( ,y)} has a first field of one space rather
   * than NULL. Composites have at least one field, so an empty
   * {@code ()} yields a single NULL field. The slice is valid only for the
   * duration of the {@code accept} call (the cursor reuses its unescape buffer),
   * so the consumer must decode it before the next field.</p>
   */
  private static void readCompositeFields(LiteralCursor cur, CompositeFieldConsumer consumer)
      throws SQLException {
    cur.skipWhitespace();
    boolean parens = cur.peek() == '(';
    if (parens) {
      cur.expect('(');
    }
    int index = 0;
    while (true) {
      cur.readVerbatim(',', ')');
      boolean isNull = !cur.tokenWasQuoted() && cur.tokenLength() == 0;
      consumer.accept(index, isNull, cur.getToken());
      index++;
      if (!cur.tryConsume(',')) {
        break;
      }
    }
    if (parens) {
      cur.expect(')');
    }
  }

  /** Receives one composite field as a borrowed, already-unquoted sequence. */
  @FunctionalInterface
  private interface CompositeFieldConsumer {
    void accept(int index, boolean isNull, CharSequence token) throws SQLException;
  }

  /**
   * Parses a PostgreSQL composite text value, e.g. {@code (val1,"val,2",)}, into the
   * raw per-field strings. A null element represents a SQL NULL attribute. The
   * surrounding parentheses are optional.
   *
   * @param text the composite text value
   * @return per-field strings (with composite escape sequences resolved)
   * @throws SQLException if the literal is malformed
   */
  public static @Nullable String[] parseCompositeText(CharSequence text) throws SQLException {
    List<@Nullable String> values = new ArrayList<>();
    LiteralCursor cur = LiteralCursor.over(text);
    readCompositeFields(cur, (index, isNull, token) ->
        values.add(isNull ? null : token.toString()));
    return values.toArray(new @Nullable String[0]);
  }

  // =========================================================================
  // Instance methods (BinaryCodec/TextCodec implementation)
  // =========================================================================

  /**
   * Creates a new instance of the given SQLData class.
   *
   * @param targetClass the SQLData class to instantiate
   * @return a new instance
   * @throws SQLException if instantiation fails
   */
  private static <T extends SQLData> T createSQLDataInstance(Class<T> targetClass) throws SQLException {
    try {
      return targetClass.getConstructor().newInstance();
    } catch (Exception ex) {
      throw Exceptions.cannotInstantiate(targetClass.getName(), ex);
    }
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Struct.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (data == null || length == 0) {
      return null;
    }
    // Decode an array-of-struct element in place: decodeBinaryFields records the
    // field offsets as absolute indices into data, so each field decodes without
    // a per-element or per-field copy.
    return decodeBinaryAsStruct(data, offset, length, type, ctx);
  }

  /**
   * Decodes binary composite data into a PgStruct with per-attribute decoding
   * routed through the codec registered for each field's OID.
   */
  private static Struct decodeBinaryAsStruct(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      List<DecodedField> binaryFields = decodeBinaryFields(data, offset, length);
      // Catalog attributes carry the modifiers the binary wire does not (it self-describes only the
      // field OID). Read them positionally, guarded by an OID match below so a wire/catalog skew
      // (e.g. dropped columns, or the anonymous RECORD with no attributes) falls back to no modifier.
      List<? extends CompositeAttribute> catalogFields = type.getAttributes();
      @Nullable Object[] attributes = new @Nullable Object[binaryFields.size()];
      for (int i = 0; i < binaryFields.size(); i++) {
        DecodedField field = binaryFields.get(i);
        if (field.isNull()) {
          attributes[i] = null;
          continue;
        }
        int fieldOid = field.getTypeOid();
        // The catalog attribute supplies the modifier and the pre-resolved descriptor the binary
        // wire lacks (it self-describes only the field OID). The OID guard keeps a wire/catalog
        // skew (dropped columns, or the anonymous RECORD with no attributes) on the plain resolve.
        TypeDescriptor fieldType = null;
        if (i < catalogFields.size()
            && catalogFields.get(i).getTypeOid() == fieldOid) {
          fieldType = attributeType(catalogFields.get(i), ctx);
        }
        if (fieldType == null) {
          fieldType = ctx.resolveType(fieldOid, -1);
        }
        BinaryCodec fieldCodec = ctx.resolveBinaryCodec(fieldOid);
        if (fieldCodec == null) {
          attributes[i] = field.getData();
        } else {
          attributes[i] = field.decode(fieldCodec, fieldType, ctx);
        }
      }
      // The struct carries the codec context (offline or connection-bound), so getValue() can
      // rebuild the text literal from the attributes either way; getAttributes() works regardless.
      return ctx.getValueFactory().createStruct(structTypeFor(type, binaryFields), attributes, null);
    } finally {
      CodecDepth.exit();
    }
  }

  /**
   * Returns the type the decoded {@link java.sql.Struct} should carry. For a named composite this
   * is {@code type} as-is (its attributes come from the catalog). For the anonymous record
   * pseudo-type (OID 2249) the catalog has no attributes, so they are synthesized from the
   * self-describing binary wire — without touching the type cache — so that the struct can rebuild
   * the {@code record_out} literal.
   */
  private static TypeDescriptor structTypeFor(TypeDescriptor type, List<DecodedField> binaryFields) {
    if (type.getOid() != Oid.RECORD) {
      return type;
    }
    List<PgField> synthesized = new ArrayList<>(binaryFields.size());
    for (int i = 0; i < binaryFields.size(); i++) {
      // Anonymous record fields have no catalog names; "fN" is positional only.
      synthesized.add(new PgField("f" + (i + 1), binaryFields.get(i).getTypeOid(), i + 1, -1));
    }
    return type.withAttributes(synthesized);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    BackpatchingByteArrayOutputStream out = new BackpatchingByteArrayOutputStream();
    if (value instanceof SQLData) {
      ctx.getValueFactory().writeSQLData(type, (SQLData) value, out);
      return out.toByteArray();
    } else if (value instanceof Struct) {
      encodeAttributes(((Struct) value).getAttributes(), type, ctx, out);
      return out.toByteArray();
    }
    throw Exceptions.cannotEncodeCompositeBinary(value);
  }

  /**
   * Writes one composite field's text value into {@code out}, streaming through the codec's
   * {@link StreamingTextCodec} form (wrapped in a record-style {@link ContainerTextEscaper} when the
   * field may need quoting) when available, and otherwise quoting the codec's {@code String}. The
   * caller writes the inter-field comma; a SQL NULL attribute is an empty field and never reaches
   * here. Shared by {@link #encodeAttributes} (the {@link Struct} path) and
   * {@link PgSQLOutputText} (the {@link SQLData} path).
   */
  public static void writeTextFieldValue(Appendable out, Object value, TypeDescriptor fieldType,
      TextCodec codec, CodecContext ctx) throws SQLException, IOException {
    if (codec instanceof StreamingTextCodec) {
      StreamingTextCodec streamingTextCodec = (StreamingTextCodec) codec;
      if (!codec.mayRequireQuoting()) {
        streamingTextCodec.encodeText(value, fieldType, ctx, out);
      } else {
        out.append('"');
        streamingTextCodec.encodeText(value, fieldType, ctx,
            new ContainerTextEscaper(out, ContainerTextEscaper.EscapeStyle.RECORD));
        out.append('"');
      }
    } else {
      appendQuotedField(out, codec.encodeText(value, fieldType, ctx));
    }
  }

  @Override
  public boolean canEncodeBinary(Object value, TypeDescriptor type, CodecContext ctx) {
    // encodeBinary serializes a Struct/SQLData attribute-by-attribute; a plain PGobject carries
    // only the composite text literal and must bind as text.
    return value instanceof Struct || value instanceof SQLData;
  }

  @Override
  public @Nullable Object decodeText(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (data == null) {
      return null;
    }
    // PgStruct extends PGobject and implements Struct, so the same return value
    // satisfies both the legacy "(PGobject) rs.getObject(i)" contract and the
    // new "(Struct) rs.getObject(i)" contract.
    return decodeWholeLiteral(data, type, ctx);
  }

  /**
   * Decodes composite text into a PgStruct with per-attribute decoding routed
   * through the text codec registered for each field's OID.
   */
  /**
   * Decodes {@code data} as a composite that occupies the whole literal, so text after the closing
   * parenthesis is an error rather than something to drop. A nested composite goes through
   * {@link #decodeTextAsStruct} directly, since its cursor is positioned inside a larger literal.
   */
  private static Struct decodeWholeLiteral(CharSequence data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    LiteralCursor cur = LiteralCursor.over(data);
    Struct struct = decodeTextAsStruct(cur, type, ctx, data);
    return struct;
  }

  private static Struct decodeTextAsStruct(LiteralCursor cur, TypeDescriptor type, CodecContext ctx,
      @Nullable CharSequence literal) throws SQLException {
    CodecDepth.enter();
    try {
      final List<? extends CompositeAttribute> fieldList = resolveFields(type, ctx);
      final int expected = fieldList.size();
      final @Nullable Object[] attributes = new @Nullable Object[expected];
      final int[] seen = {0};
      // Decode each field from its borrowed slice in place: no per-field String,
      // and nested composites/arrays recurse through the child codec's own cursor.
      readCompositeFields(cur, (index, isNull, token) -> {
        if (index >= expected) {
          // A named composite (expected > 0) must match its literal exactly: surface a
          // catalog/literal field-count skew (e.g. after ALTER TYPE ADD ATTRIBUTE) instead of
          // silently dropping the surplus fields and corrupting the value. The anonymous record
          // pseudo-type has no catalog attributes (expected == 0) and so cannot be validated;
          // its fields are tolerated as before, and PgStruct.getValue() keeps the raw literal.
          if (expected == 0) {
            return;
          }
          throw Exceptions.compositeTextHasMoreAttributes(type.getName().getLocalName(), expected);
        }
        seen[0] = index + 1;
        if (isNull) {
          attributes[index] = null;
          return;
        }
        CompositeAttribute field = fieldList.get(index);
        int fieldOid = field.getTypeOid();
        // The descriptor already carries the attribute modifier (atttypmod), so a
        // modifier-sensitive field such as numeric(10,2) decodes to its declared scale.
        TypeDescriptor fieldType = attributeType(field, ctx);
        TextCodec fieldCodec = ctx.resolveTextCodec(fieldOid);
        if (fieldCodec == null) {
          attributes[index] = token.toString();
        } else {
          attributes[index] = fieldCodec.decodeText(token, fieldType, ctx);
        }
      });
      if (expected > 0 && seen[0] != expected) {
        // Fewer literal fields than the type declares is the same catalog/literal skew as the
        // surplus case above; reject it rather than NULL-filling the missing trailing fields.
        throw Exceptions.compositeTextAttributeCountMismatch(type.getName().getLocalName(), expected, seen[0]);
      }
      // Text transfer carries no per-field OIDs, so an anonymous record keeps the
      // fieldless pseudo-type; getValue() still works because the raw server
      // literal is recorded verbatim by the caller. The SPI type reaching this codec is
      // the driver's own PgType, which PgStruct (internal) carries. The struct also carries the
      // codec context so getValue() can rebuild the literal offline.
      return ctx.getValueFactory().createStruct(type, attributes, literal);
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (value instanceof SQLData) {
      StringBuilder sb = new StringBuilder();
      ctx.getValueFactory().writeSQLData(type, (SQLData) value, sb);
      return sb.toString();
    }
    if (value instanceof Struct) {
      // Check Struct before PGobject: PgStruct extends PGobject AND implements
      // Struct, and the PGobject view's value field is intentionally null —
      // taking the PGobject branch would bind an empty string and the server
      // would reject it as a malformed record literal.
      Struct struct = (Struct) value;
      return encodeAttributesAsText(struct.getAttributes(), type, ctx);
    }
    if (value instanceof PGobject) {
      String strValue = ((PGobject) value).getValue();
      return strValue != null ? strValue : "";
    }
    if (value instanceof String) {
      // A bare String is already the composite's text literal — the form
      // createArrayOf("composite_type", new String[]{"(1,2)"}) produces. Emit it
      // verbatim, matching PGobject.getValue() and the legacy array encoder.
      return (String) value;
    }
    throw Exceptions.cannotConvertToComposite(value);
  }

  /**
   * Streaming variant: writes the composite text representation directly into
   * {@code out}. For the {@link Struct} path this avoids the intermediate
   * String produced by {@link #encodeText(Object, TypeDescriptor, CodecContext)} —
   * worthwhile when the composite is itself an element of an array, because
   * the array codec then wraps {@code out} in an
   * {@link ContainerTextEscaper} and array-level escaping happens char-by-char
   * during the write rather than as a second pass over a buffered String.
   *
   * <p>The {@link SQLData} path streams too: its {@code writeSQL} callbacks append the composite
   * literal straight into {@code out}, its {@code record_out} escaping compounding through any
   * enclosing {@link ContainerTextEscaper}, with no intermediate {@code String}.</p>
   */
  @Override
  public void encodeText(Object value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    if (value instanceof SQLData) {
      ctx.getValueFactory().writeSQLData(type, (SQLData) value, out);
      return;
    }
    if (value instanceof Struct) {
      encodeAttributes(((Struct) value).getAttributes(), type, ctx, out);
      return;
    }
    if (value instanceof PGobject) {
      String strValue = ((PGobject) value).getValue();
      if (strValue != null) {
        out.append(strValue);
      }
      return;
    }
    // Anything else: defer to the non-streaming path.
    out.append(encodeText(value, type, ctx));
  }

  /** Streaming counterpart of {@link #encodeAttributesAsText}. */
  private static void encodeAttributes(
      @Nullable Object[] attributes,
      TypeDescriptor compositeType,
      CodecContext ctx,
      Appendable out) throws SQLException {
    List<? extends CompositeAttribute> fields = resolveFields(compositeType, ctx);
    if (fields.size() != attributes.length) {
      throw Exceptions.compositeAttributeCountMismatch(compositeType.getName().getLocalName(), fields.size(), attributes.length);
    }
    CodecDepth.enter();
    try {
      out.append('(');
      for (int i = 0; i < attributes.length; i++) {
        if (i > 0) {
          out.append(',');
        }
        Object attr = attributes[i];
        if (attr == null) {
          continue;
        }
        CompositeAttribute field = fields.get(i);
        int fieldOid = field.getTypeOid();
        // For a nested anonymous record (OID 2249) fieldTypeFor swaps in the decoded PgStruct's own
        // synthesized-field type, so the composite codec streams the nested record recursively
        // instead of rebuilding its record_out literal as a String.
        TypeDescriptor fieldType = fieldTypeFor(fieldOid, attr, ctx);
        TextCodec codec = ctx.resolveTextCodec(fieldOid);
        if (codec == null) {
          throw Exceptions.noTextCodecForCompositeField(fieldOid, field.getName(), compositeType.getName().getLocalName());
        }
        writeTextFieldValue(out, attr, fieldType, codec, ctx);
      }
      out.append(')');
    } catch (IOException e) {
      throw Exceptions.errorWritingComposite(e);
    } finally {
      CodecDepth.exit();
    }
  }

  /** Streaming variant of {@link #encodeBinary(Object, TypeDescriptor, CodecContext)}. */
  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingByteArrayOutputStream out) throws SQLException, IOException {
    if (value instanceof SQLData) {
      ctx.getValueFactory().writeSQLData(type, (SQLData) value, out);
      return;
    }
    if (value instanceof Struct) {
      // Struct fast path: stream each attribute straight into the sink, back-patching
      // per-field length prefixes, so no per-field byte[] is materialized.
      encodeAttributes(((Struct) value).getAttributes(), type, ctx, out);
      return;
    }
    // Plain PGobject values never reach here: canEncodeBinary() gates them out and they bind as
    // text. Any other value has no streaming form, so materialize it and copy it in.
    out.write(encodeBinary(value, type, ctx));
  }

  private static void encodeAttributes(
      @Nullable Object[] attributes,
      TypeDescriptor compositeType,
      CodecContext ctx,
      BackpatchingByteArrayOutputStream out) throws SQLException {
    List<? extends CompositeAttribute> fields = resolveFields(compositeType, ctx);
    if (fields.size() != attributes.length) {
      throw Exceptions.compositeAttributeCountMismatch(compositeType.getName().getLocalName(), fields.size(), attributes.length);
    }
    CodecDepth.enter();
    try {
      out.writeInt32(fields.size());
      for (int i = 0; i < attributes.length; i++) {
        CompositeAttribute field = fields.get(i);
        int fieldOid = field.getTypeOid();
        // type oid
        out.writeInt32(fieldOid);
        Object attr = attributes[i];
        if (attr == null) {
          out.writeInt32(-1);
          continue;
        }
        TypeDescriptor fieldType = fieldTypeFor(fieldOid, attr, ctx);
        BinaryCodec codec = ctx.resolveBinaryCodec(fieldOid);
        if (codec == null) {
          throw Exceptions.noBinaryCodecForCompositeField(fieldOid, field.getName(), compositeType.getName().getLocalName());
        }
        CodecFormatSupport.writeBinaryElement(out, attr, codec, fieldType, ctx);
      }
    } catch (IOException e) {
      throw Exceptions.errorWritingComposite(e);
    } finally {
      CodecDepth.exit();
    }
  }

  /**
   * Returns the descriptor to encode a composite field's value against, shared by the binary and
   * text composite streaming paths.
   *
   * <p>The composite wire carries only each field's type OID, so a nested anonymous record reports
   * the {@code record} pseudo-type OID (2249), which resolves to a fieldless descriptor — encoding
   * a struct against it would fail the attribute-count check. A {@link PgStruct} decoded from the
   * wire already carries the fields synthesized from its own self-description, so prefer that type
   * for a record-typed field, letting the composite codec stream the nested record recursively
   * rather than rebuilding its {@code record_out} literal as a String.</p>
   */
  private static TypeDescriptor fieldTypeFor(int fieldOid, Object attr, CodecContext ctx)
      throws SQLException {
    if (fieldOid == Oid.RECORD && attr instanceof PgStruct) {
      PgType carried = ((PgStruct) attr).getResolvedType();
      if (carried != null && carried.hasFieldsLoaded()) {
        return carried;
      }
    }
    return ctx.resolveType(fieldOid);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    if (data == null || length == 0) {
      return null;
    }

    // Handle SQLData implementations — read the nested record straight off the enclosing buffer via the
    // slice constructor, so a composite field decodes in place without copying its bytes out first.
    if (SQLData.class.isAssignableFrom(targetClass)) {
      CodecDepth.enter();
      try {
        Class<? extends SQLData> sqlDataClass = (Class<? extends SQLData>) targetClass;
        SQLData sqlData = createSQLDataInstance(sqlDataClass);
        SQLInput input = ctx.getValueFactory().createSQLInput(type, data, offset, length);
        sqlData.readSQL(input, type.getFormattedName());
        return (T) sqlData;
      } finally {
        CodecDepth.exit();
      }
    }

    // Structured access — build a PgStruct with per-field decoded attributes.
    if (targetClass == Struct.class || targetClass == PgStruct.class) {
      return (T) decodeBinaryAsStruct(data, offset, length, type, ctx);
    }

    // Legacy access — return the typed PGobject wrapper produced by decodeBinary.
    if (targetClass == PGobject.class || targetClass == Object.class) {
      return (T) decodeBinary(data, offset, length, type, ctx);
    }

    throw Exceptions.cannotConvertCompositeTo(targetClass.getName());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(CharSequence data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (data.length() == 0) {
      return null;
    }
    // No eager toString(): each branch below either consumes the literal within this call or
    // materializes exactly what it retains, so a borrowed slice costs nothing on the paths that
    // never keep it.

    // Handle SQLData implementations
    if (SQLData.class.isAssignableFrom(targetClass)) {
      CodecDepth.enter();
      try {
        Class<? extends SQLData> sqlDataClass = (Class<? extends SQLData>) targetClass;
        SQLData sqlData = createSQLDataInstance(sqlDataClass);
        // The input is consumed by readSQL before this returns, so it can read the slice in place.
        SQLInput input = ctx.getValueFactory().createSQLInput(type, data);
        sqlData.readSQL(input, type.getFormattedName());
        return (T) sqlData;
      } finally {
        CodecDepth.exit();
      }
    }

    // Structured access — build a PgStruct with per-field decoded attributes.
    if (targetClass == Struct.class || targetClass == PgStruct.class) {
      return (T) decodeWholeLiteral(data, type, ctx);
    }

    // Legacy access — return the typed PGobject wrapper produced by decodeText.
    if (targetClass == PGobject.class || targetClass == Object.class) {
      return (T) decodeText(data, type, ctx);
    }

    // String is allowed
    if (targetClass == String.class) {
      return (T) data.toString();
    }

    throw Exceptions.cannotConvertCompositeTo(targetClass.getName());
  }

}
