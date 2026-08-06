/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One edge-case value for a PostgreSQL type: a name, the literal text, and the Java value to bind when one
 * exists.
 *
 * <p>The literal drives the read side ({@code '<literal>'::<type>}); the value drives the write/bind side.
 * Most catalogues exercise the read side only and carry a {@code null} value throughout; each says so in
 * its own comment. A catalogue that does bind still leaves the value {@code null} where no Java type
 * represents the literal, such as {@code NaN} in {@code numeric}, which no {@code BigDecimal} holds.
 * Catalogues live next to this class,
 * one per type (see {@link NumericEdgeCases}, {@link Int4EdgeCases}, {@link Float8EdgeCases}, ...).
 */
public final class EdgeCase {
  private final String name;
  private final String literal;
  private final @Nullable Object value;

  public EdgeCase(String name, String literal, @Nullable Object value) {
    this.name = name;
    this.literal = literal;
    this.value = value;
  }

  /** A short, stable identifier, for example {@code int_max_plus_0_5}. */
  public String name() {
    return name;
  }

  /** The PostgreSQL literal text, for example {@code 2147483647.5}, {@code NaN} or {@code infinity}. */
  public String literal() {
    return literal;
  }

  /**
   * The Java value for binding, or {@code null} when this catalogue exercises the read side only, or when
   * no Java type represents the literal.
   */
  public @Nullable Object value() {
    return value;
  }

  @Override
  public String toString() {
    return name + "(" + literal + ")";
  }
}
