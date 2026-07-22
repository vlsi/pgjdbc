/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code tstzrange} values (timestamp-with-time-zone range). The bounds carry explicit UTC
 * offsets, so the range exercises the offset-aware decode; this catalogue covers a closed range, the empty
 * range, both-unbounded, a lower-unbounded range, and a bound whose time is exactly midnight (a
 * zero-fraction bound that has surfaced {@code ...00:00:00.0} rendering divergences).
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class TstzRangeEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private TstzRangeEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("closed_offsets", "[\"2020-01-01 00:00:00+03\",\"2020-12-31 12:00:00-05\"]"));
    out.add(at("empty", "empty"));
    out.add(at("both_unbounded", "(,)"));
    out.add(at("lower_unbounded", "(,\"2020-12-31 12:00:00+00\"]"));
    out.add(at("zero_fraction", "[\"2020-06-01 00:00:00+00\",\"2020-06-02 00:00:00+00\"]"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
