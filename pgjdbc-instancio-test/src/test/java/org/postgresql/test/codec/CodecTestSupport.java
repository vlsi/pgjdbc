/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.codec;

import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;

/**
 * Small helpers shared by the Instancio-driven codec property tests.
 */
final class CodecTestSupport {

  private CodecTestSupport() {
  }

  /**
   * A connectionless {@link CodecContext} for unit tests (UTC, UTF-8, no java.time preference),
   * built by {@link TestCodecContext} which now lives in the shared {@code testkit} module.
   *
   * <p>The scalar codecs exercised here use the context only for charset / timestamp helpers (or
   * not at all), so this connectionless context is sufficient. Codecs that need a live connection
   * — the array/composite walkers and {@code RangeCodec}, which look up element codecs through the
   * registry — are still out of scope.</p>
   */
  static final CodecContext CTX = TestCodecContext.create();

  /** A connectionless context that returns {@code java.time} types from the temporal codecs. */
  static CodecContext javaTimeCtx() {
    return TestCodecContext.create(true, true, true, true, true);
  }

  /**
   * Builds a minimal base-type {@link PgType} for a pg_catalog scalar type.
   */
  static PgType type(String schemalessName, String fullName, int oid) {
    return new PgType(new ObjectName("pg_catalog", schemalessName), fullName, oid,
        'b', 'N', -1, 0, 0, 0);
  }
}
