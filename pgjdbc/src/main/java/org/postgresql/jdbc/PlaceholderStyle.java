/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.ParameterContext;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * @see org.postgresql.PGProperty#PLACEHOLDER_STYLE
 */
public enum PlaceholderStyle {
  ANY("any"),
  JDBC("jdbc"),
  NAMED("named"),
  NATIVE("native"),
  NONE("none");

  private final String value;

  PlaceholderStyle(String value) {
    this.value = value;
  }

  public static PlaceholderStyle of(@Nullable String mode) throws PSQLException {
    for (PlaceholderStyle placeholderStyle : values()) {
      if (placeholderStyle.value.equals(mode)) {
        return placeholderStyle;
      }
    }
    throw new PSQLException(GT.tr("Parameter value must be one of {0} but was: {1}",
        Arrays.stream(PlaceholderStyle.values()).map(
            PlaceholderStyle::value).collect(Collectors.toList()), mode), PSQLState.INVALID_PARAMETER_VALUE);
  }

  public boolean isAcceptedBySetting(ParameterContext.BindStyle bindStyle) {
    return this == PlaceholderStyle.ANY || this.name().equals(bindStyle.name());
  }

  public String value() {
    return value;
  }
}
