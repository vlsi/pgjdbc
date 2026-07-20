/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;
import org.postgresql.util.ByteConverter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An in-memory {@link OutputStream} that collects the bytes written to it, and the sink the driver
 * hands a {@link StreamingBinaryCodec} when it materializes a value.
 *
 * <p>Write a length placeholder with {@link #reserveInt32()}, stream the body straight into the same
 * buffer, then fill the placeholder in with {@link #setInt32At(int, int)} once the length is known —
 * no per-element temporary {@code byte[]}. {@link #toByteArray()} returns what was written.</p>
 *
 * <p>This is the sink to write a codec's unit test against:</p>
 *
 * <pre>{@code
 * BackpatchingByteArrayOutputStream out = new BackpatchingByteArrayOutputStream();
 * codec.encodeBinary(value, type, ctx, out);
 * assertArrayEquals(expectedWire, out.toByteArray());
 * }</pre>
 *
 * <p>Implementing a sink like this by hand for a test is possible but not advisable:
 * {@link #reserveInt32()} and {@link #setInt32At(int, int)} patch bytes already in the buffer, and a
 * sink that gets that subtly wrong turns a codec's tests into a broken oracle.</p>
 *
 * <p>The bytes live in a list of segments rather than in one array, so growth allocates a new
 * segment instead of copying everything written so far. Encoding a large value therefore costs one
 * allocation per segment plus the single copy {@link #toByteArray()} makes, not the repeated
 * copy-on-grow a {@link java.io.ByteArrayOutputStream} pays. Segments stay in the write order, and a
 * value reserved with {@link #reserveInt32()} never straddles a segment boundary, so back-patching
 * remains a single {@code int} store.</p>
 *
 * <p>The class stays an {@link OutputStream} so a codec can pass it to any API that writes bytes,
 * and it keeps the {@code toByteArray()}, {@code size()}, {@code reset()} and
 * {@code writeTo(OutputStream)} methods a Java developer expects of an in-memory byte sink. Do not
 * narrow it to a bare interface.</p>
 *
 * <p>Not thread-safe: {@link #reserveInt32()} followed by {@link #setInt32At(int, int)} is a
 * multi-step sequence, and none of the methods lock. Confine an instance to one thread.</p>
 *
 * @since 42.8.0
 */
@Experimental("Streaming codec API is experimental and may change in future releases")
public final class BackpatchingByteArrayOutputStream extends OutputStream {

  /**
   * Smallest segment the sink allocates. Encoding a scalar rarely needs more than this, so a fresh
   * sink stays cheap.
   */
  private static final int MIN_SEGMENT_SIZE = 32;

  /**
   * Largest segment the sink allocates. Past this size, doubling would waste more memory than the
   * extra segment costs in indirection.
   */
  private static final int MAX_SEGMENT_SIZE = 1 << 20;

  /**
   * Shared initial value of {@link #segmentStarts}: a sink that never outgrows its first segment —
   * the common case for a scalar — then allocates no starts array of its own. Never written to;
   * {@link #addSegment(int)} replaces it with a private copy before storing the second entry.
   */
  private static final int[] SINGLE_SEGMENT_START = {0};

  /**
   * The written bytes, in write order. Every segment but the last is full up to its used length; the
   * last one is the one being appended to.
   */
  private final List<byte[]> segments = new ArrayList<>();

  /**
   * Absolute position of each segment's first byte, so {@link #setInt32At(int, int)} can map a
   * position back to a segment. Entry {@code i} is valid for {@code i < segments.size()}.
   */
  private int[] segmentStarts = SINGLE_SEGMENT_START;

  /**
   * The segment currently being appended to, always the last element of {@link #segments}.
   */
  private byte[] tail;

  /**
   * Absolute position of {@link #tail}'s first byte.
   */
  private int tailStart;

  /**
   * Number of bytes used in {@link #tail}.
   */
  private int tailUsed;

  /**
   * Creates a sink with the default initial capacity.
   */
  public BackpatchingByteArrayOutputStream() {
    this(MIN_SEGMENT_SIZE);
  }

  /**
   * Creates a sink whose first segment holds {@code initialCapacity} bytes, so a value of a known or
   * estimated size fits without allocating a second segment.
   *
   * @param initialCapacity the first segment's size in bytes
   * @throws IllegalArgumentException if {@code initialCapacity} is negative
   */
  public BackpatchingByteArrayOutputStream(int initialCapacity) {
    if (initialCapacity < 0) {
      throw new IllegalArgumentException("Negative initial capacity: " + initialCapacity);
    }
    tail = new byte[Math.max(initialCapacity, MIN_SEGMENT_SIZE)];
    segments.add(tail);
  }

  /**
   * @return current write position (== size of data written so far).
   */
  public int position() {
    return tailStart + tailUsed;
  }

  /**
   * @return number of bytes written so far, the same value as {@link #position()}
   */
  public int size() {
    return position();
  }

  /**
   * Appends a single byte, taking the low 8 bits of {@code b} ({@code b & 0xFF})
   * and ignoring the higher bits, matching {@link java.io.OutputStream#write(int)}.
   *
   * @param b the byte to append, in its low 8 bits
   */
  public void writeByte(int b) {
    write(b);
  }

  @Override
  public void write(int b) {
    if (tailUsed == tail.length) {
      addSegment(1);
    }
    tail[tailUsed++] = (byte) b;
  }

  @Override
  public void write(byte[] b, int off, int len) {
    if (off < 0 || len < 0 || len > b.length - off) {
      throw new IndexOutOfBoundsException(
          "offset " + off + " and length " + len + " are out of bounds for array of length "
              + b.length);
    }
    int remaining = len;
    int from = off;
    while (remaining > 0) {
      if (tailUsed == tail.length) {
        // The whole remainder is known here, so size the new segment for all of it rather than
        // let it grow chunk by chunk. That bounds this loop at two iterations.
        addSegment(remaining);
      }
      int chunk = Math.min(remaining, tail.length - tailUsed);
      System.arraycopy(b, from, tail, tailUsed, chunk);
      tailUsed += chunk;
      from += chunk;
      remaining -= chunk;
    }
  }

  // Each writer reserves (which may start a new segment and reassign tail) into a local BEFORE
  // touching tail, so the ByteConverter call reads the current segment, not a stale reference.

  /**
   * Appends a big-endian 2-byte signed integer (the low 16 bits of {@code value}).
   *
   * @param value the {@code int16} to append
   */
  public void writeInt16(int value) {
    int offset = reserve(2);
    ByteConverter.int2(tail, offset, value);
  }

  /**
   * Appends a big-endian 4-byte signed integer at the current position.
   *
   * @param value the {@code int32} to append
   */
  public void writeInt32(int value) {
    int offset = reserve(4);
    ByteConverter.int4(tail, offset, value);
  }

  /**
   * Appends a big-endian 8-byte signed integer.
   *
   * @param value the {@code int64} to append
   */
  public void writeInt64(long value) {
    int offset = reserve(8);
    ByteConverter.int8(tail, offset, value);
  }

  /**
   * Appends a big-endian IEEE-754 4-byte float (via {@link Float#floatToIntBits}).
   *
   * @param value the {@code float4} to append
   */
  public void writeFloat4(float value) {
    int offset = reserve(4);
    ByteConverter.float4(tail, offset, value);
  }

  /**
   * Appends a big-endian IEEE-754 8-byte double (via {@link Double#doubleToLongBits}).
   *
   * @param value the {@code float8} to append
   */
  public void writeFloat8(double value) {
    int offset = reserve(8);
    ByteConverter.float8(tail, offset, value);
  }

  /**
   * Reserves a 4-byte slot at the current position and returns the index of the
   * slot, which can later be passed to {@link #setInt32At(int, int)}.
   */
  public int reserveInt32() {
    int offset = reserve(4);
    return tailStart + offset;
  }

  /**
   * Overwrites the {@code int32} slot at the given position with {@code value}.
   * Caller must have previously written or reserved at least 4 bytes at that
   * position.
   */
  public void setInt32At(int position, int value) {
    if (position < 0 || position > size() - 4) {
      throw new IndexOutOfBoundsException(
          "int32 patch position " + position + " is out of bounds (count=" + size() + ")");
    }
    if (position >= tailStart) {
      ByteConverter.int4(tail, position - tailStart, value);
      return;
    }
    int index = segmentIndex(position);
    int offset = position - segmentStarts[index];
    if (offset <= usedLength(index) - 4) {
      ByteConverter.int4(segments.get(index), offset, value);
      return;
    }
    // A slot reserved through reserveInt32() never straddles segments, but a hand-computed
    // position can, and the wire bytes have to come out the same either way.
    for (int i = 0; i < 4; i++) {
      setByteAt(position + i, (byte) (value >>> (24 - 8 * i)));
    }
  }

  /**
   * Returns the bytes written so far, in write order, as a newly allocated array.
   *
   * @return a copy of this sink's contents
   */
  public byte[] toByteArray() {
    byte[] result = new byte[size()];
    for (int i = 0; i < segments.size(); i++) {
      System.arraycopy(segments.get(i), 0, result, segmentStarts[i], usedLength(i));
    }
    return result;
  }

  /**
   * Writes the bytes written so far to {@code out}, in write order.
   *
   * @param out the stream to write to
   * @throws IOException if {@code out} throws
   */
  public void writeTo(OutputStream out) throws IOException {
    for (int i = 0; i < segments.size(); i++) {
      out.write(segments.get(i), 0, usedLength(i));
    }
  }

  /**
   * Discards the bytes written so far so the sink can be reused. The first segment is kept, later
   * ones are released.
   */
  public void reset() {
    if (segments.size() > 1) {
      segments.subList(1, segments.size()).clear();
    }
    tail = segments.get(0);
    tailStart = 0;
    tailUsed = 0;
  }

  /**
   * Reserves {@code n} bytes within the current segment, starting a new segment if they do not fit,
   * and returns the offset of the reserved region within {@link #tail}. The reserved bytes are
   * always contiguous, so the caller can hand {@code tail} to {@link ByteConverter}.
   */
  private int reserve(int n) {
    if (n > tail.length - tailUsed) {
      addSegment(n);
    }
    int offset = tailUsed;
    tailUsed += n;
    return offset;
  }

  /**
   * Appends a segment of at least {@code minCapacity} bytes and makes it the current one. Callers
   * pass everything they already know they need — a caller that is mid-write passes the whole
   * remainder, not one byte — since a segment cannot grow after the fact. Beyond that minimum the
   * segment doubles the total capacity up to {@link #MAX_SEGMENT_SIZE}, so writing {@code n} bytes
   * costs O(log n) allocations and no copying.
   */
  private void addSegment(int minCapacity) {
    int start = position();
    int capacity = Math.max(minCapacity, Math.min(MAX_SEGMENT_SIZE, Math.max(start, MIN_SEGMENT_SIZE)));
    int index = segments.size();
    if (index == segmentStarts.length) {
      segmentStarts = Arrays.copyOf(segmentStarts, segmentStarts.length * 2);
    }
    tail = new byte[capacity];
    segments.add(tail);
    segmentStarts[index] = start;
    tailStart = start;
    tailUsed = 0;
  }

  /**
   * Returns the index of the segment holding the byte at absolute {@code position}, which must be
   * within the bytes written so far.
   */
  private int segmentIndex(int position) {
    int index = Arrays.binarySearch(segmentStarts, 0, segments.size(), position);
    return index >= 0 ? index : -index - 2;
  }

  /**
   * Returns how many bytes of segment {@code index} hold written data. A segment can be left short
   * of its capacity when a reservation did not fit into its remaining room.
   */
  private int usedLength(int index) {
    return index == segments.size() - 1
        ? tailUsed
        : segmentStarts[index + 1] - segmentStarts[index];
  }

  private void setByteAt(int position, byte value) {
    if (position >= tailStart) {
      tail[position - tailStart] = value;
      return;
    }
    int index = segmentIndex(position);
    segments.get(index)[position - segmentStarts[index]] = value;
  }
}
