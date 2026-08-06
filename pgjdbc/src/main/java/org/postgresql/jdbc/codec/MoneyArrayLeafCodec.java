/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingByteArrayOutputStream;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.core.Oid;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PGmoney;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Leaf-level codec for {@code money[]} arrays.
 *
 * <p>{@code getArray().getArray()} on a money array returns {@code Double[]}, matching the legacy
 * decoder's component type. Only the component type carries over: the legacy element parse ran
 * {@code "$1.50"} through {@code Double.parseDouble} and read the binary {@code int8} as an IEEE
 * double. Text elements parse through {@link PGmoney}, which handles the currency symbol,
 * parentheses and grouping separators; binary elements are the {@code int8} smallest-unit value,
 * decoded as {@code value / 100.0}.</p>
 */
final class MoneyArrayLeafCodec implements ArrayLeafCodec {

  static final MoneyArrayLeafCodec INSTANCE = new MoneyArrayLeafCodec();

  /**
   * Smallest units per currency unit on binary transfer, at the default scale of {@code 2}.
   *
   * <p>PostgreSQL {@code cash} stores the amount as an {@code int8} scaled by the locale's fraction
   * digits, which the protocol does not carry; {@link MoneyCodec} states why, and why
   * {@code money}/{@code money[]} are kept text-only on receive
   * ({@link MoneyCodec#decodesBinary()} is {@code false}). The binary methods in this class exist
   * for the two cases that still reach them: a {@code money[]} field of a binary composite, whose
   * fields are decoded in binary without consulting that flag, and a caller that has explicitly
   * opted {@code money} into binary transfer.</p>
   */
  private static final double SCALE = 100.0;

  private MoneyArrayLeafCodec() {
  }

  @Override
  public int getElementOid() {
    return Oid.MONEY;
  }

  @Override
  public Class<?> getPrimitiveComponentType() {
    return Double.class;
  }

  @Override
  public Class<?> getBoxedComponentType() {
    return Double.class;
  }

  @Override
  public boolean writeLeaf(Object leaf, BackpatchingByteArrayOutputStream out, CodecContext ctx)
      throws IOException, SQLException {
    if (!(leaf instanceof Object[])) {
      throw unsupportedLeaf(leaf, ctx);
    }
    boolean hasNulls = false;
    for (Object element : (Object[]) leaf) {
      if (element == null) {
        out.writeInt32(-1);
        hasNulls = true;
      } else {
        out.writeInt32(8);
        out.writeInt64(Math.round(toDouble(element) * SCALE));
      }
    }
    return hasNulls;
  }

  @Override
  public void readLeaf(byte[] data, int[] cursor, Object leaf, CodecContext ctx) throws SQLException {
    if (!(leaf instanceof Object[])) {
      throw unsupportedLeaf(leaf, ctx);
    }
    @Nullable Object[] arr = (@Nullable Object[]) leaf;
    int pos = cursor[0];
    for (int i = 0; i < arr.length; i++) {
      int len = ByteConverter.int4(data, pos);
      pos += 4;
      if (len == -1) {
        arr[i] = null;
      } else {
        arr[i] = ByteConverter.int8(data, pos) / SCALE;
        pos += len;
      }
    }
    cursor[0] = pos;
  }

  @Override
  public void appendLeaf(Appendable out, Object leaf, char delimiter, CodecContext ctx)
      throws SQLException, IOException {
    if (!(leaf instanceof Object[])) {
      throw unsupportedLeaf(leaf, ctx);
    }
    Object[] arr = (Object[]) leaf;
    for (int i = 0; i < arr.length; i++) {
      if (i > 0) {
        out.append(delimiter);
      }
      Object element = arr[i];
      if (element == null) {
        out.append("NULL");
      } else {
        // A bare numeric literal is accepted by the server's money input regardless of lc_monetary.
        out.append(Double.toString(toDouble(element)));
      }
    }
  }

  @Override
  public void readLeafText(LiteralCursor cur, Object leaf, char delimiter, CodecContext ctx)
      throws SQLException {
    if (!(leaf instanceof Object[])) {
      throw unsupportedLeaf(leaf, ctx);
    }
    @Nullable Object[] arr = (@Nullable Object[]) leaf;
    for (int i = 0; i < arr.length; i++) {
      if (i > 0) {
        cur.expect(delimiter);
      }
      cur.readArrayElement(delimiter);
      if (!cur.tokenWasQuoted() && cur.tokenEquals("NULL")) {
        arr[i] = null;
      } else {
        String token = cur.getToken().toString();
        arr[i] = new PGmoney(token).val;
      }
    }
  }

  private static double toDouble(Object value) throws SQLException {
    if (value instanceof PGmoney) {
      return ((PGmoney) value).val;
    }
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    if (value instanceof String) {
      return new PGmoney((String) value).val;
    }
    throw Exceptions.cannotEncode(value, "money");
  }
}
