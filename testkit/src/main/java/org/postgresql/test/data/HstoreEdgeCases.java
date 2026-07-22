/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code hstore} values (a key/value map, from the {@code hstore} extension). This catalogue
 * covers a single pair, several pairs, a {@code NULL} value, the empty map (whose text form is the empty
 * string, which the decoder handles as a special case), an empty-string value, and keys and values that
 * carry spaces, an apostrophe, an embedded double quote, and a backslash.
 *
 * <p>The literals are the {@code hstore} text-input form; callers splice them into {@code '<literal>'::
 * hstore} and must SQL-escape the apostrophe in the {@code apostrophe} case. Callers must also gate this on
 * the extension being installed, since {@code hstore} has no fixed OID until then.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class HstoreEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private HstoreEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("single", "a=>1"));
    out.add(at("pairs", "a=>1,b=>2"));
    out.add(at("null_value", "a=>NULL"));
    out.add(at("empty_map", ""));
    out.add(at("empty_value", "a=>\"\""));
    out.add(at("spaces", "\"a b\"=>\"c d\""));
    out.add(at("apostrophe", "k=>\"it's, x\""));
    out.add(at("embedded_quote", "k=>\"a\\\"b\""));
    out.add(at("backslash", "k=>\"a\\\\b\""));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
