/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.api.codec.BackpatchingByteArrayOutputStream;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.StreamingTextCodec;
import org.postgresql.api.codec.TypeName;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.PgType;
import org.postgresql.test.util.StrangeOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Random;

/**
 * Unit tests for the streaming codec path that exist without an active
 * database connection. Integration coverage (composite-element arrays in
 * binary + text mode, with quotes / backslashes / nulls in nested values)
 * lives in {@code NestedStructArrayRoundtripTest}.
 */
class StreamingCodecTest {

  private PgType int4Type;

  @BeforeEach
  void setUp() {
    int4Type = new PgType(
        TypeName.of("pg_catalog", "int4"),
        "integer",
        Oid.INT4,
        'b', 'N', -1, 0, 0, 0);
  }

  // ---------------- Streaming vs materializing form agree ----------------

  @Test
  void int4_stringFormMatchesStreamingText() throws SQLException {
    // Int4Codec implements both the String-returning encodeText and the streaming
    // Appendable form; they must produce identical output.
    String viaString = Int4Codec.INSTANCE.encodeText(42, int4Type, null);
    StringBuilder sb = new StringBuilder();
    try {
      Int4Codec.INSTANCE.encodeText(42, int4Type, null, sb);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    assertEquals(viaString, sb.toString());
    assertEquals("42", viaString);
  }

  @Test
  void int4_byteArrayFormMatchesStreamingBinary() throws SQLException, IOException {
    // The byte[]-returning encodeBinary and the streaming sink form must produce identical bytes.
    byte[] viaArray = Int4Codec.INSTANCE.encodeBinary(42, int4Type, null);
    BackpatchingByteArrayOutputStream out = new BackpatchingByteArrayOutputStream();
    Int4Codec.INSTANCE.encodeBinary(42, int4Type, null, out);
    assertArrayEquals(viaArray, out.toByteArray());
  }

  // ---------------- BackpatchingByteArrayOutputStream ----------------

  @Test
  void backpatch_reserveThenPatch_writesAtRecordedPosition() {
    BackpatchingByteArrayOutputStream out = new BackpatchingByteArrayOutputStream();
    assertTrue(out instanceof BackpatchingByteArrayOutputStream);
    out.write(0xAB); // 1 byte prefix
    int slot = out.reserveInt32();
    assertEquals(5, out.position());
    out.write(0xCD); // 1 byte payload after slot
    out.setInt32At(slot, 0x12345678);
    byte[] bytes = out.toByteArray();
    assertEquals(6, bytes.length);
    assertEquals((byte) 0xAB, bytes[0]);
    assertEquals((byte) 0x12, bytes[1]);
    assertEquals((byte) 0x34, bytes[2]);
    assertEquals((byte) 0x56, bytes[3]);
    assertEquals((byte) 0x78, bytes[4]);
    assertEquals((byte) 0xCD, bytes[5]);
  }

  @Test
  void backpatch_writeInt32_appendsAtCurrentPosition() {
    BackpatchingByteArrayOutputStream out = new BackpatchingByteArrayOutputStream();
    out.write(0xAB);
    out.writeInt32(0x12345678);
    out.write(0xCD);
    byte[] bytes = out.toByteArray();
    assertEquals(6, bytes.length);
    assertEquals((byte) 0xAB, bytes[0]);
    assertEquals((byte) 0x12, bytes[1]);
    assertEquals((byte) 0x34, bytes[2]);
    assertEquals((byte) 0x56, bytes[3]);
    assertEquals((byte) 0x78, bytes[4]);
    assertEquals((byte) 0xCD, bytes[5]);
  }

  @Test
  void backpatch_segmentedWrites_matchByteArrayOutputStream() throws IOException {
    // The sink stores bytes in segments, so the interesting cases are the ones that cross a segment
    // boundary. Drive it past several boundaries against ByteArrayOutputStream as the oracle.
    BackpatchingByteArrayOutputStream out = new BackpatchingByteArrayOutputStream();
    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    Random random = new Random(20260720L);
    // StrangeOutputStream splits each write into randomly sized pieces, so the byte[] path is
    // driven with non-zero offsets and single-byte writes, not just whole-array appends.
    OutputStream fragmented = new StrangeOutputStream(out, 20260720L, 0.1);
    for (int i = 0; i < 10_000; i++) {
      switch (i % 5) {
        case 0:
          fragmented.write(i);
          expected.write(i);
          break;
        case 1:
          out.writeInt16(i);
          expected.write(new byte[]{(byte) (i >>> 8), (byte) i});
          break;
        case 2:
          out.writeInt64(i);
          for (int shift = 56; shift >= 0; shift -= 8) {
            expected.write((byte) (((long) i) >>> shift));
          }
          break;
        default:
          byte[] chunk = new byte[random.nextInt(600)];
          random.nextBytes(chunk);
          fragmented.write(chunk);
          expected.write(chunk);
          break;
      }
      assertEquals(expected.size(), out.position(), "position after op " + i);
    }
    assertArrayEquals(expected.toByteArray(), out.toByteArray());

    ByteArrayOutputStream copy = new ByteArrayOutputStream();
    out.writeTo(copy);
    assertArrayEquals(expected.toByteArray(), copy.toByteArray());
  }

  @Test
  void backpatch_slotReservedBeforeSegmentBoundary_isPatchedInPlace() {
    // A 4-byte slot must never straddle a segment boundary, and patching it must stay visible once
    // later writes have moved the sink into further segments.
    for (int prefix = 0; prefix < 200; prefix++) {
      BackpatchingByteArrayOutputStream out = new BackpatchingByteArrayOutputStream(1);
      for (int i = 0; i < prefix; i++) {
        out.write(0xAB);
      }
      int slot = out.reserveInt32();
      assertEquals(prefix, slot);
      byte[] body = new byte[500];
      Arrays.fill(body, (byte) 0xCD);
      out.write(body, 0, body.length);
      out.setInt32At(slot, 0x12345678);

      byte[] bytes = out.toByteArray();
      assertEquals(prefix + 4 + body.length, bytes.length);
      assertEquals((byte) 0x12, bytes[prefix]);
      assertEquals((byte) 0x34, bytes[prefix + 1]);
      assertEquals((byte) 0x56, bytes[prefix + 2]);
      assertEquals((byte) 0x78, bytes[prefix + 3]);
      if (prefix > 0) {
        assertEquals((byte) 0xAB, bytes[prefix - 1]);
      }
      assertEquals((byte) 0xCD, bytes[prefix + 4]);
    }
  }

  @Test
  void backpatch_reset_reusesTheSink() {
    BackpatchingByteArrayOutputStream out = new BackpatchingByteArrayOutputStream(1);
    out.write(new byte[4096], 0, 4096);
    out.reset();
    assertEquals(0, out.position());
    assertArrayEquals(new byte[0], out.toByteArray());

    out.writeInt32(0x12345678);
    assertArrayEquals(new byte[]{0x12, 0x34, 0x56, 0x78}, out.toByteArray());
  }

  @Test
  void backpatch_patchPositionOutOfBounds_isRejected() {
    BackpatchingByteArrayOutputStream out = new BackpatchingByteArrayOutputStream();
    out.writeInt32(0);
    assertThrows(IndexOutOfBoundsException.class, () -> out.setInt32At(1, 0));
    assertThrows(IndexOutOfBoundsException.class, () -> out.setInt32At(-1, 0));
  }

  // ---------------- EscapingAppendable ----------------

  @Test
  void escapingAppendable_quotesAndBackslashesGetBackslashPrefix() throws IOException {
    StringWriter sink = new StringWriter();
    ContainerTextEscaper esc = new ContainerTextEscaper(sink, ContainerTextEscaper.EscapeStyle.ARRAY);
    esc.append("say \"hi\" with \\ slash");
    assertEquals("say \\\"hi\\\" with \\\\ slash", sink.toString());
  }

  @Test
  void escapingAppendable_subsequenceRespectsRange() throws IOException {
    StringWriter sink = new StringWriter();
    ContainerTextEscaper esc = new ContainerTextEscaper(sink, ContainerTextEscaper.EscapeStyle.ARRAY);
    esc.append("XX\"YY", 2, 3); // only the quote
    assertEquals("\\\"", sink.toString());
  }

  @Test
  void escapingAppendable_arrayElementQuotesProtectCompositeSyntax() throws IOException {
    StringWriter sink = new StringWriter();
    sink.append('"');
    ContainerTextEscaper esc = new ContainerTextEscaper(sink, ContainerTextEscaper.EscapeStyle.ARRAY);
    esc.append("(a,\"b\")");
    sink.append('"');
    assertEquals("\"(a,\\\"b\\\")\"", sink.toString());
  }

  @Test
  void escapingAppendable_layersForArrayOfCompositeWithNestedArray() throws IOException {
    StringWriter sink = new StringWriter();

    // Outer array quotes its composite element and applies array-level escaping.
    sink.append('"');
    ContainerTextEscaper arrayElementEscaper = new ContainerTextEscaper(sink, ContainerTextEscaper.EscapeStyle.ARRAY);

    // Composite writes a quoted field into the already array-escaped sink.
    arrayElementEscaper.append('(');
    arrayElementEscaper.append('"');

    // Nested array field writes through a second escaping layer. Quotes and
    // backslashes now receive both composite-field and array-element escaping.
    ContainerTextEscaper compositeFieldEscaper = new ContainerTextEscaper(arrayElementEscaper, ContainerTextEscaper.EscapeStyle.ARRAY);
    compositeFieldEscaper.append("{\"a\\\"b\"}");

    arrayElementEscaper.append('"');
    arrayElementEscaper.append(')');
    sink.append('"');

    assertEquals("\"(\\\"{\\\\\\\"a\\\\\\\\\\\\\\\"b\\\\\\\"}\\\")\"", sink.toString());
  }

  @Test
  void escapingAppendable_recordStyleDoublesInsteadOfPrefixing() throws IOException {
    StringWriter sink = new StringWriter();
    ContainerTextEscaper esc =
        new ContainerTextEscaper(sink, ContainerTextEscaper.EscapeStyle.RECORD);
    esc.append("say \"hi\" with \\ slash");
    assertEquals("say \"\"hi\"\" with \\\\ slash", sink.toString());
  }

  @Test
  void appendQuotedArrayStyle_prefixesQuotesAndBackslashes() throws IOException {
    StringWriter sink = new StringWriter();
    ContainerTextEscaper.appendQuotedArrayStyle(sink, "say \"hi\" with \\ slash");
    assertEquals("\"say \\\"hi\\\" with \\\\ slash\"", sink.toString());
  }

  @Test
  void appendQuotedRecordStyle_doublesQuotesAndBackslashes() throws IOException {
    StringWriter sink = new StringWriter();
    ContainerTextEscaper.appendQuotedRecordStyle(sink, "say \"hi\" with \\ slash");
    assertEquals("\"say \"\"hi\"\" with \\\\ slash\"", sink.toString());
  }

  @Test
  void appendQuoted_emptyValueIsAnEmptyQuotedLiteral() {
    StringBuilder sb = new StringBuilder();
    ContainerTextEscaper.appendQuotedArrayStyle(sb, "");
    ContainerTextEscaper.appendQuotedRecordStyle(sb, "");
    assertEquals("\"\"\"\"", sb.toString());
  }

  @Test
  void appendQuoted_stringBuilderOverloadMatchesTheAppendableOne() throws IOException {
    String value = "a\"b\\c";
    StringWriter viaAppendable = new StringWriter();
    ContainerTextEscaper.appendQuotedArrayStyle((Appendable) viaAppendable, value);
    StringBuilder viaStringBuilder = new StringBuilder();
    ContainerTextEscaper.appendQuotedArrayStyle(viaStringBuilder, value);
    assertEquals(viaAppendable.toString(), viaStringBuilder.toString());
  }

  // ---------------- INT4 array leaf via streaming ----------------

  @Test
  void int4ArrayLeaf_streamingText_equalsNonStreaming() throws SQLException, IOException {
    Integer[] input = {1, null, -7, Integer.MAX_VALUE};
    String viaString = MultiDimArrayText.encode(input, ',', null, Int4ArrayLeafCodec.INSTANCE);
    StringBuilder sb = new StringBuilder();
    MultiDimArrayText.encode(input, ',', sb, null, Int4ArrayLeafCodec.INSTANCE);
    assertEquals(viaString, sb.toString());
    assertEquals("{1,NULL,-7,2147483647}", viaString);
  }

  @Test
  void int4ArrayLeaf_streamingBinary_equalsNonStreaming() throws SQLException, IOException {
    int[] input = {7, -42, 0, 1};
    byte[] viaArray = MultiDimArrayBinary.encode(input, null, Int4ArrayLeafCodec.INSTANCE);
    BackpatchingByteArrayOutputStream out = new BackpatchingByteArrayOutputStream();
    MultiDimArrayBinary.encode(input, out, null, Int4ArrayLeafCodec.INSTANCE);
    assertArrayEquals(viaArray, out.toByteArray());
  }

  // ---------------- Interface inheritance ----------------

  @Test
  void scalarCodecs_implementStreamingInterfaces() {
    assertTrue(Int4Codec.INSTANCE instanceof StreamingTextCodec,
        "Int4Codec should opt into StreamingTextCodec");
    assertTrue(Int4Codec.INSTANCE instanceof StreamingBinaryCodec,
        "Int4Codec should opt into StreamingBinaryCodec");
    assertTrue(CompositeCodec.INSTANCE instanceof StreamingTextCodec,
        "CompositeCodec should opt into StreamingTextCodec");
    assertTrue(CompositeCodec.INSTANCE instanceof StreamingBinaryCodec,
        "CompositeCodec should opt into StreamingBinaryCodec");
  }

  @Test
  void textFamily_doesNotStream() {
    // The String-natural leaves (text, varchar, bpchar, name, "char") deliberately do NOT stream: a String
    // must be materialised into charset bytes before it is written either way, so a streaming encoder saves
    // nothing over the byte[]/String form (unlike a fixed-width primitive). As a container element the whole
    // family encodes through the non-streaming path. Locked in here so it is not re-added by reflex.
    for (Codec codec : new Codec[]{TextCodec.INSTANCE, VarcharCodec.INSTANCE, BpcharCodec.INSTANCE,
        NameCodec.INSTANCE, CharCodec.INSTANCE}) {
      assertFalse(codec instanceof StreamingTextCodec,
          () -> codec.getClass().getSimpleName() + " must not stream text");
      assertFalse(codec instanceof StreamingBinaryCodec,
          () -> codec.getClass().getSimpleName() + " must not stream binary");
    }
  }

  @Test
  void arrayCodecs_implementStreamingInterfaces() {
    assertTrue(ArrayCodec.INSTANCE instanceof StreamingTextCodec,
        "ArrayCodec should opt into StreamingTextCodec");
    assertTrue(ArrayCodec.INSTANCE instanceof StreamingBinaryCodec,
        "ArrayCodec should opt into StreamingBinaryCodec");
    assertTrue(Int4Codec.INSTANCE instanceof ArrayElementCodec,
        "Int4Codec should advertise an array fast-leaf via ArrayElementCodec");
  }

  @Test
  void int4ArrayLeaf_streamingBinaryHeaderMatchesNonStreaming() throws SQLException, IOException {
    Integer[] input = {10, 20, 30};
    byte[] viaArray = MultiDimArrayBinary.encode(input, null, Int4ArrayLeafCodec.INSTANCE);
    BackpatchingByteArrayOutputStream out = new BackpatchingByteArrayOutputStream();
    MultiDimArrayBinary.encode(input, out, null, Int4ArrayLeafCodec.INSTANCE);
    byte[] streamed = out.toByteArray();
    assertNotNull(viaArray);
    assertArrayEquals(viaArray, streamed);
  }
}
