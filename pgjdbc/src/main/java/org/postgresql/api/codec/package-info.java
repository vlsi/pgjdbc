/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

/**
 * The public codec SPI: encode and decode PostgreSQL values without going through the driver's
 * internals. {@link org.postgresql.api.codec.Codec} and its format-specific subinterfaces are what
 * an application implements to add a type; {@link org.postgresql.api.codec.CodecContext} carries the
 * settings a codec reads while doing so.
 *
 * <h2>Method naming</h2>
 *
 * <p>One piece of state is spelled one way throughout, so that finding either half of a pair tells
 * you the other — {@code CodecContext} reports the state and
 * {@link org.postgresql.api.codec.CodecContextBuilder} sets it.</p>
 *
 * <ol>
 *   <li>An accessor returning something other than {@code boolean} is {@code getX()}:
 *       {@code getCharset()}, {@code getClientTimeZone()}, {@code getAttributes()}.</li>
 *   <li>A {@code boolean} query never takes {@code get}; it reads as a sentence about the receiver.
 *       {@code isX()} says what the receiver is — {@code isArray()}, {@code isDomain()}. A verb in
 *       the third person says what it does or allows — {@code usesIntegerDateTimes()},
 *       {@code convertsBooleanToNumeric()}, {@code prefersLocalDate()}, {@code encodesBinary()},
 *       {@code canDecodeText()}, {@code mayRequireQuoting()}.</li>
 *   <li>A fluent setter repeats its accessor's name: identically for a {@code boolean}
 *       ({@code usesIntegerDateTimes(boolean)} sets what {@code usesIntegerDateTimes()} reports),
 *       and without the {@code get} otherwise ({@code charset(Charset)} sets what
 *       {@code getCharset()} reports).</li>
 * </ol>
 *
 * <p>Three setters have no accessor to mirror, because the context publishes a resolved view rather
 * than what was registered: {@code codecLookup(…)}, {@code type(…)}, and {@code types(…)} feed
 * {@link org.postgresql.api.codec.CodecContext#resolveCodec(int)} and
 * {@link org.postgresql.api.codec.CodecContext#resolveType(int)}.</p>
 *
 * @since 42.8.0
 */
package org.postgresql.api.codec;
