/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.consumer.composite;

import org.postgresql.api.codec.TextCodec;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.PgType;

import java.sql.SQLException;

public final class ServicePointCodec implements TextCodec {
  @Override
  public String getTypeName() {
    return "point";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return ServicePoint.class;
  }

  @Override
  public Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    String trimmed = data.trim();
    if (!trimmed.startsWith("(") || !trimmed.endsWith(")")) {
      throw new SQLException("Unexpected point format: " + data);
    }
    String[] parts = trimmed.substring(1, trimmed.length() - 1).split(",", -1);
    if (parts.length != 2) {
      throw new SQLException("Unexpected point format: " + data);
    }
    return new ServicePoint(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    if (!(value instanceof ServicePoint)) {
      throw new SQLException("Unsupported point value: " + value);
    }
    ServicePoint point = (ServicePoint) value;
    return "(" + point.x + "," + point.y + ")";
  }
}
