/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import java.io.IOException;
import java.io.StringWriter;

/**
 * An {@link Appendable} that also accepts a Java integer as its decimal text without allocating an
 * intermediate {@code String} — the text counterpart of {@link BackpatchingByteArrayOutputStream}'s typed
 * writers.
 *
 * <p>{@link StringBuilder} already offers {@code append(int)}/{@code append(long)} that write the
 * digits straight into its buffer. The static {@link #appendInt}/{@link #appendLong} helpers take
 * that allocation-free path when the target is a {@link StringBuilder} and fall back to
 * {@link Integer#toString(int)} otherwise. A wrapping text sink (such as the composite/array escaping
 * sink) overrides {@code append(int)}/{@code append(long)} to forward to its {@link StringBuilder}
 * delegate, so its digits reach the buffer without an intermediate {@code String}. A sink that leaves
 * the defaults in place gets the correct but allocating fallback.</p>
 *
 * <h2>Why the encode signatures still take {@code Appendable}</h2>
 *
 * <p>{@link StreamingTextCodec#encodeText} and {@link PrimitiveTextEncoder} declare
 * {@link Appendable}, not this interface, and that asymmetry with
 * {@link BackpatchingByteArrayOutputStream} on the binary side is deliberate. Do not "fix" it.</p>
 *
 * <p>The common target is a plain {@link StringBuilder}, which does not implement this interface.
 * Declaring {@code PrimitiveTextSink} as the parameter type would force every caller to wrap its
 * {@code StringBuilder} in an adapter, adding an allocation to the path this interface exists to
 * keep allocation-free. The static helpers avoid that: they dispatch on the concrete target once
 * per call and reach {@code StringBuilder.append(int)} directly, so the fast path is available to
 * an {@code Appendable} parameter without anyone implementing this interface.</p>
 *
 * <p>Implement it when a sink wraps a {@link StringBuilder} behind transformation — the
 * composite and array escaping sinks do — so that digits still reach the underlying buffer
 * unboxed.</p>
 *
 * @since 42.8.0
 */
@Experimental("Streaming codec API is experimental and may change in future releases")
public interface PrimitiveTextSink extends Appendable {

  /**
   * Appends the decimal text of {@code value}, like {@link StringBuilder#append(int)}, without
   * allocating an intermediate {@code String}.
   *
   * @param value the value to append
   * @return this sink
   * @throws IOException if the underlying sink throws
   */
  default PrimitiveTextSink append(int value) throws IOException {
    append(Integer.toString(value));
    return this;
  }

  /**
   * Appends the decimal text of {@code value}, like {@link StringBuilder#append(long)}, without
   * allocating an intermediate {@code String}.
   *
   * @param value the value to append
   * @return this sink
   * @throws IOException if the underlying sink throws
   */
  default PrimitiveTextSink append(long value) throws IOException {
    append(Long.toString(value));
    return this;
  }

  /**
   * Appends the text of {@code value}, like {@link StringBuilder#append(float)}, without allocating
   * an intermediate {@code String}.
   *
   * @param value the value to append
   * @return this sink
   * @throws IOException if the underlying sink throws
   */
  default PrimitiveTextSink append(float value) throws IOException {
    append(Float.toString(value));
    return this;
  }

  /**
   * Appends the text of {@code value}, like {@link StringBuilder#append(double)}, without allocating
   * an intermediate {@code String}.
   *
   * @param value the value to append
   * @return this sink
   * @throws IOException if the underlying sink throws
   */
  default PrimitiveTextSink append(double value) throws IOException {
    append(Double.toString(value));
    return this;
  }

  /**
   * Appends {@code value}'s decimal text to {@code out}, avoiding an intermediate {@code String} when
   * {@code out} is a {@link StringBuilder} and falling back to {@link Integer#toString(int)}
   * otherwise.
   *
   * @param out the sink to append to
   * @param value the value to append
   * @throws IOException if {@code out} throws
   */
  static void appendInt(Appendable out, int value) throws IOException {
    if (out instanceof StringBuilder) {
      ((StringBuilder) out).append(value);
    } else if (out instanceof PrimitiveTextSink) {
      ((PrimitiveTextSink) out).append(value);
    } else if (out instanceof StringWriter) {
      ((StringWriter) out).getBuffer().append(value);
    } else {
      out.append(Integer.toString(value));
    }
  }

  /**
   * Appends {@code value}'s decimal text to {@code out}, avoiding an intermediate {@code String} when
   * {@code out} is a {@link StringBuilder} and falling back to {@link Long#toString(long)}
   * otherwise.
   *
   * @param out the sink to append to
   * @param value the value to append
   * @throws IOException if {@code out} throws
   */
  static void appendLong(Appendable out, long value) throws IOException {
    if (out instanceof StringBuilder) {
      ((StringBuilder) out).append(value);
    } else if (out instanceof PrimitiveTextSink) {
      ((PrimitiveTextSink) out).append(value);
    } else if (out instanceof StringWriter) {
      ((StringWriter) out).getBuffer().append(value);
    } else {
      out.append(Long.toString(value));
    }
  }

  /**
   * Appends {@code value}'s text to {@code out}, avoiding an intermediate {@code String} when
   * {@code out} is a {@link StringBuilder} and falling back to {@link Float#toString(float)}
   * otherwise.
   *
   * @param out the sink to append to
   * @param value the value to append
   * @throws IOException if {@code out} throws
   */
  static void appendFloat(Appendable out, float value) throws IOException {
    if (out instanceof StringBuilder) {
      ((StringBuilder) out).append(value);
    } else if (out instanceof PrimitiveTextSink) {
      ((PrimitiveTextSink) out).append(value);
    } else if (out instanceof StringWriter) {
      ((StringWriter) out).getBuffer().append(value);
    } else {
      out.append(Float.toString(value));
    }
  }

  /**
   * Appends {@code value}'s text to {@code out}, avoiding an intermediate {@code String} when
   * {@code out} is a {@link StringBuilder} and falling back to {@link Double#toString(double)}
   * otherwise.
   *
   * @param out the sink to append to
   * @param value the value to append
   * @throws IOException if {@code out} throws
   */
  static void appendDouble(Appendable out, double value) throws IOException {
    if (out instanceof StringBuilder) {
      ((StringBuilder) out).append(value);
    } else if (out instanceof PrimitiveTextSink) {
      ((PrimitiveTextSink) out).append(value);
    } else if (out instanceof StringWriter) {
      ((StringWriter) out).getBuffer().append(value);
    } else {
      out.append(Double.toString(value));
    }
  }
}
