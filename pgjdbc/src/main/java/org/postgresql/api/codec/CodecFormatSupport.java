/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Format-capability facts about a {@link Codec}, and the enforcement helpers built on them.
 *
 * <p>The four {@code can…} predicates are the authoritative answer to whether a codec reads or
 * writes a given wire {@link Format}. Each folds the {@code instanceof} check and the codec's own
 * capability methods ({@link BinaryCodec#decodesBinary()}, {@link BinaryCodec#canEncodeBinary},
 * {@link TextCodec#decodesText()}) into one result, so callers that pick or enforce a format decide
 * it the same way instead of testing {@code instanceof} and the capability flags separately.</p>
 *
 * <p>The narrowing casts stay confined here: a predicate answers the capability question, and the
 * matching {@code require…} helper returns the narrowed codec or fails with a consistent error. The
 * write-side binary decision has two depths: {@link #canWriteBinary} is the recursive negotiation
 * check (the whole type tree plus the value, run once per bind to pick binary vs text), while
 * {@link #requireBinaryEncoder} is the local enforcement gate (this level's flag plus the value, run
 * as the encode recurses). The read-side and text checks depend only on the codec.</p>
 *
 * <p>{@link #requireBinaryEncoder} is the single enforcement gate for a binary payload: every
 * sink-based write funnels through {@link #writeBinary}, and the {@code byte[]}-materializing paths
 * call it directly, so no {@code encodeBinary} bytes reach a binary wire without it.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public final class CodecFormatSupport {

  private CodecFormatSupport() {
  }

  /**
   * Whether {@code codec} reads the binary wire form for its type.
   *
   * @param codec the codec to inspect
   * @return true if {@code codec} is a {@link BinaryCodec} that decodes binary
   */
  public static boolean canReadBinary(Codec codec) {
    return codec instanceof BinaryCodec && ((BinaryCodec) codec).decodesBinary();
  }

  /**
   * Whether {@code value} can be bound as a real binary payload for {@code type}, end to end: it is a
   * {@link BinaryCodec}, {@code type} and every type nested inside it are binary-capable
   * ({@link BinaryCodec#canEncodeBinaryType}), and the value-level {@link BinaryCodec#canEncodeBinary}
   * accepts this value. This is the negotiation check — {@code chooseBindFormat} and
   * {@link org.postgresql.jdbc.PgArray#toBytes()} gate the binary path on it, so a container over a
   * text-only child (a {@code time} subtype in a range, say) binds as text rather than failing at
   * encode. The recursive type walk runs once per bind here, not per element: the enforcement gate
   * {@link #requireBinaryEncoder} checks each level locally as the encode recurses.
   *
   * @param codec the codec to inspect
   * @param value the value to be encoded
   * @param type the target type metadata
   * @param ctx the codec context
   * @return true if {@code codec} is a {@link BinaryCodec} that can binary-bind {@code value}
   * @throws SQLException if type metadata cannot be resolved
   */
  public static boolean canWriteBinary(Codec codec, Object value, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (!(codec instanceof BinaryCodec)) {
      return false;
    }
    BinaryCodec binary = (BinaryCodec) codec;
    return binary.canEncodeBinaryType(type, ctx) && binary.canEncodeBinary(value, type, ctx);
  }

  /**
   * Whether {@code codec} reads the text wire form for its type.
   *
   * @param codec the codec to inspect
   * @return true if {@code codec} is a {@link TextCodec} that decodes text
   */
  public static boolean canReadText(Codec codec) {
    return codec instanceof TextCodec && ((TextCodec) codec).decodesText();
  }

  /**
   * Whether {@code codec} can write text. Text encoding is mandatory once a codec implements
   * {@link TextCodec}, so this is exactly {@code codec instanceof TextCodec}.
   *
   * @param codec the codec to inspect
   * @return true if {@code codec} is a {@link TextCodec}
   */
  public static boolean canWriteText(Codec codec) {
    return codec instanceof TextCodec;
  }

  // The require… helpers narrow the codec for a fixed, caller-chosen format, or fail. They exist so
  // Codecs.encode/decode enforce the requested format through the same predicates a negotiating
  // caller would consult, rather than each site re-deriving the instanceof + capability check.

  /**
   * Narrows {@code codec} to a {@link BinaryCodec} that can binary-encode {@code value} for
   * {@code type} at this level, or fails. This is the enforcement gate for a real binary payload: it
   * is the last check before {@link BinaryCodec#encodeBinary} bytes reach a binary wire, so a codec
   * that cannot produce binary for this value is rejected here rather than writing text-shaped bytes
   * into the binary format. {@link #writeBinary} funnels every sink-based binary write through it, and
   * the {@code byte[]}-materializing paths ({@link Codecs#encode}, {@code DomainCodec}) call it
   * directly.
   *
   * <p>The check is local — {@link BinaryCodec#encodesBinary()} plus the value-level
   * {@link BinaryCodec#canEncodeBinary} — not the recursive {@link #canWriteBinary}. Each container
   * level runs its own gate as the encode recurses, so the whole tree is covered one frame at a time;
   * the recursive type walk belongs to the once-per-bind negotiation, not to every element write.
   *
   * @param codec the codec resolved for {@code type}
   * @param value the value to be encoded
   * @param type the target type metadata
   * @param ctx the codec context
   * @return {@code codec} narrowed to {@link BinaryCodec}
   * @throws SQLException if {@code codec} cannot binary-encode {@code value}, or resolution fails
   */
  public static BinaryCodec requireBinaryEncoder(Codec codec, Object value, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (!(codec instanceof BinaryCodec)) {
      throw Exceptions.noCodecForFormat(type, "binary");
    }
    BinaryCodec binary = (BinaryCodec) codec;
    if (!binary.encodesBinary() || !binary.canEncodeBinary(value, type, ctx)) {
      throw Exceptions.noCodecForFormat(type, "binary");
    }
    return binary;
  }

  static TextCodec requireTextEncoder(Codec codec, TypeDescriptor type) throws SQLException {
    if (!canWriteText(codec)) {
      throw Exceptions.noCodecForFormat(type, "text");
    }
    return (TextCodec) codec;
  }

  static BinaryCodec requireBinaryDecoder(Codec codec, TypeDescriptor type) throws SQLException {
    if (!canReadBinary(codec)) {
      throw Exceptions.noCodecForFormat(type, "binary");
    }
    return (BinaryCodec) codec;
  }

  static TextCodec requireTextDecoder(Codec codec, TypeDescriptor type) throws SQLException {
    if (!canReadText(codec)) {
      throw Exceptions.noCodecForFormat(type, "text");
    }
    return (TextCodec) codec;
  }

  /**
   * Writes {@code value}'s binary body into {@code out}, taking the streaming path when the codec
   * offers one.
   *
   * <p>A {@link StreamingBinaryCodec} encodes straight into the sink; any other
   * {@link BinaryCodec} encodes into a {@code byte[]} first, which is then copied in. The body
   * carries no length prefix — {@link #writeBinaryElement} adds one where the format needs it.</p>
   *
   * <p>Every sink-based binary write funnels through here, so the {@link #requireBinaryEncoder}
   * gate runs once for every value before its bytes reach the sink: a codec that cannot binary-encode
   * {@code value} — a delegating codec whose child is text-only, a plain {@code PGobject} bound to a
   * composite — fails here instead of writing text-shaped bytes into the binary wire.</p>
   *
   * @param out the sink the body is written to
   * @param value the value to encode
   * @param codec the codec that encodes {@code value}
   * @param type the type to encode {@code value} as
   * @param ctx the codec context supplying connection settings
   * @throws SQLException if {@code codec} cannot binary-encode {@code value}, or encoding fails
   * @throws IOException if {@code out} throws
   */
  public static void writeBinary(BackpatchingByteArrayOutputStream out, Object value, BinaryCodec codec,
      TypeDescriptor type, CodecContext ctx) throws SQLException, IOException {
    requireBinaryEncoder(codec, value, type, ctx);
    if (codec instanceof StreamingBinaryCodec) {
      ((StreamingBinaryCodec) codec).encodeBinary(value, type, ctx, out);
    } else {
      out.write(codec.encodeBinary(value, type, ctx));
    }
  }

  /**
   * Writes {@code value} into {@code out} as one length-prefixed container element.
   *
   * <p>Reserves the four-byte length slot, delegates the body to
   * {@link #writeBinary(BackpatchingByteArrayOutputStream, Object, BinaryCodec, TypeDescriptor,
   * CodecContext)}, then back-patches the slot with the number of bytes the body took. A streaming
   * codec therefore needs no intermediate {@code byte[]} even though the length precedes the body
   * on the wire.</p>
   *
   * @param out the sink the element is written to
   * @param value the value to encode
   * @param codec the codec that encodes {@code value}
   * @param type the type to encode {@code value} as
   * @param ctx the codec context supplying connection settings
   * @throws SQLException if encoding fails
   * @throws IOException if {@code out} throws
   */
  public static void writeBinaryElement(BackpatchingByteArrayOutputStream out, Object value,
      BinaryCodec codec, TypeDescriptor type,
      CodecContext ctx) throws IOException, SQLException {
    int lengthSlot = out.reserveInt32();
    int start = out.position();
    writeBinary(out, value, codec, type, ctx);
    out.setInt32At(lengthSlot, out.position() - start);
  }
}
