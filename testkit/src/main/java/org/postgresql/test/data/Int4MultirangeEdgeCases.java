/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code int4multirange} values (a set of non-overlapping {@code int4range} ranges, PostgreSQL
 * 14+). This catalogue covers the empty multirange, a single range, two disjoint ranges, two overlapping
 * ranges that PostgreSQL merges into one, and an unbounded range inside the set.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}). Callers must gate this on server version 14+.
 */
public final class Int4MultirangeEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private Int4MultirangeEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("empty_set", "{}"));
    out.add(at("single", "{[1,3)}"));
    out.add(at("disjoint", "{[1,3),[5,8)}"));
    out.add(at("merged", "{[1,3),[2,5)}"));
    out.add(at("unbounded", "{(,3),[5,)}"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
