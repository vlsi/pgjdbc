/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import org.postgresql.api.codec.JavaTimePreferences;
import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.OffsetDateTime;

/**
 * Reader cases a fuzz campaign found, replayed as plain {@code @Test}s so they stay covered without one.
 *
 * <p>{@link CoercionFuzzSupport#run} carries the assertions: the {@code ReadCoercions} outcome for each
 * wire format, and cross-format parity over the value. A case earns a place here when a campaign had to
 * search for it -- the value sits somewhere a generator reaches and a hand-written catalogue does not, so
 * nothing else would replay it on a normal build.
 */
class CoercionReaderExampleTest {

  /**
   * A {@code timestamptz} whose UTC offset carries the time of day across midnight, read as
   * {@code java.sql.Time}.
   *
   * <p>The two wires disagreed by exactly one day: the binary branch truncates the instant to the day,
   * while the text branch went through {@code TimestampUtils.toTime}, which anchors the time of day to
   * 1970-01-01 in the literal's <em>own</em> offset -- 1970-01-02 in UTC once the offset crosses midnight.
   * Both render as {@code 13:36:44}, so only a comparison sees it, which is why the parity oracle found it
   * and reading the values did not.
   */
  @Test
  void timestamptzReadTimeCrossesMidnightInItsOwnOffset() throws SQLException {
    CoercionFuzzSupport.run(new CoercionCase(PgTypeDescriptors.scalar(Oid.TIMESTAMPTZ),
        OffsetDateTime.parse("9938-03-17T23:08:44.481764-14:28"),
        SqlInputReader.READ_TIME, null, JavaTimePreferences.NONE));
  }
}
