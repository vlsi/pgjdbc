/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code int8range} values. A discrete range like {@code int4range}, so this catalogue keeps only
 * the forms that carry new information over {@link Int4RangeEdgeCases}: bounds beyond the {@code int4} range
 * (so an {@code int4} decode would overflow), the empty range, both-unbounded, and one ordinary interval.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class Int8RangeEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private Int8RangeEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("ordinary", "[1,10)"));
    out.add(at("bigint_bounds", "[10000000000,20000000000)"));
    out.add(at("empty", "empty"));
    out.add(at("both_unbounded", "(,)"));
    out.add(at("full", "[-9223372036854775808,9223372036854775807]"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
