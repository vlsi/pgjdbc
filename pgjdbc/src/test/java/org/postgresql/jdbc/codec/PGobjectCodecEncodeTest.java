/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.CodecFormatSupport;
import org.postgresql.api.codec.TypeName;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PGBinaryObject;
import org.postgresql.util.PGobject;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

/**
 * {@link PGobjectCodec} encodes an instance of the class registered through
 * {@code addDataType} from the representation that instance carries, rather than handing it to a
 * delegate that only knows its own Java class. Without this the adapter refuses on encode the very
 * value it produced on decode, and a value read from a binary column -- whose
 * {@link PGobject#getValue()} is null -- is written back as SQL NULL.
 */
class PGobjectCodecEncodeTest {

  private CodecContext ctx;
  private PgType int4Type;
  private PGobjectCodec codec;

  @BeforeEach
  void setUp() {
    ctx = TestCodecContext.create();
    int4Type = new PgType(
        TypeName.of("pg_catalog", "int4"),
        "integer",
        Oid.INT4,
        'b', 'N', -1, 0, 0, 0
    );
    codec = new PGobjectCodec(BinaryPgObject.class, Int4Codec.INSTANCE);
  }

  @Test
  void binaryObjectWritesTheBinaryFormItCarries() throws SQLException {
    BinaryPgObject value = BinaryPgObject.ofBytes(42);

    assertTrue(CodecFormatSupport.canWriteBinary(codec, value, int4Type, ctx),
        "an instance carrying a binary form binds binary");
    assertArrayEquals(int4Bytes(42), codec.encodeBinary(value, int4Type, ctx),
        "encodeBinary must return the object's own bytes");
  }

  @Test
  void binaryObjectWithoutTextRendersItsBytesAsText() throws SQLException {
    BinaryPgObject value = BinaryPgObject.ofBytes(42);

    assertEquals("42", codec.encodeText(value, int4Type, ctx),
        "an instance with no text of its own renders its bytes through the delegate");
  }

  @Test
  void textCarryingObjectNegotiatesTextAndKeepsItsLiteral() throws SQLException {
    BinaryPgObject value = BinaryPgObject.ofText("42");

    assertFalse(CodecFormatSupport.canWriteBinary(codec, value, int4Type, ctx),
        "an instance with no binary form must negotiate text rather than fail at encode");
    assertEquals("42", codec.encodeText(value, int4Type, ctx),
        "encodeText must return the object's own literal");
  }

  @Test
  void aPlainSubclassKeepsItsLiteralToo() throws SQLException {
    PGobjectCodec plainCodec = new PGobjectCodec(PGobject.class, Int4Codec.INSTANCE);
    PGobject value = new PGobject();
    value.setType("int4");
    value.setValue("42");

    assertFalse(CodecFormatSupport.canWriteBinary(plainCodec, value, int4Type, ctx),
        "a subclass with no binary form of its own binds text");
    assertEquals("42", plainCodec.encodeText(value, int4Type, ctx),
        "encodeText must return the object's own literal");
  }

  @Test
  void aValueOfAnotherClassStillGoesToTheDelegate() throws SQLException {
    assertTrue(CodecFormatSupport.canWriteBinary(codec, 42, int4Type, ctx),
        "the delegate answers for a value the registered class does not cover");
    assertArrayEquals(int4Bytes(42), codec.encodeBinary(42, int4Type, ctx),
        "the delegate encodes a value of its own Java class");
    assertEquals("42", codec.encodeText(42, int4Type, ctx),
        "the delegate encodes a value of its own Java class");
  }

  private static byte[] int4Bytes(int value) {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, value);
    return data;
  }

  /** A {@link PGBinaryObject} subclass carrying either a binary form or a text one. */
  public static final class BinaryPgObject extends PGobject implements PGBinaryObject {
    private byte @Nullable [] bytes;

    BinaryPgObject() {
      setType("int4");
    }

    static BinaryPgObject ofBytes(int value) {
      BinaryPgObject object = new BinaryPgObject();
      object.bytes = int4Bytes(value);
      return object;
    }

    static BinaryPgObject ofText(String value) throws SQLException {
      BinaryPgObject object = new BinaryPgObject();
      object.setValue(value);
      return object;
    }

    @Override
    public int lengthInBytes() {
      byte[] bytes = this.bytes;
      return bytes == null ? 0 : bytes.length;
    }

    @Override
    public void toBytes(byte[] target, int offset) {
      System.arraycopy(castNonNullBytes(), 0, target, offset, lengthInBytes());
    }

    @Override
    public void setByteValue(byte[] value, int offset) {
      byte[] copy = new byte[value.length - offset];
      System.arraycopy(value, offset, copy, 0, copy.length);
      bytes = copy;
    }

    private byte[] castNonNullBytes() {
      byte[] bytes = this.bytes;
      if (bytes == null) {
        throw new IllegalStateException("no binary value has been set");
      }
      return bytes;
    }
  }
}
