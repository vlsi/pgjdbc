/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.codec;

import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;

/**
 * Small helpers shared by the Instancio-driven codec property tests.
 */
final class CodecTestSupport {

  private CodecTestSupport() {
  }

  /**
   * The scalar codecs exercised in this module never dereference the {@link CodecContext} on their
   * binary path (verified against the source), so a {@code null} context is sufficient. This keeps
   * the module independent of the package-private test {@code CodecContext} factory that lives in
   * the {@code :postgresql} test sources and is therefore not reachable from a separate module.
   *
   * <p>Codecs whose binary path <em>does</em> use the context (text {@code int} parsing, the
   * temporal codecs, and the array/composite walkers) are intentionally out of scope here; wiring
   * those up cleanly would mean exposing a shared test {@code CodecContext} via Gradle test
   * fixtures.</p>
   */
  static final CodecContext NO_CTX = null;

  /**
   * Builds a minimal base-type {@link PgType} for a pg_catalog scalar type.
   */
  static PgType type(String schemalessName, String fullName, int oid) {
    return new PgType(new ObjectName("pg_catalog", schemalessName), fullName, oid,
        'b', 'N', -1, 0, 0, 0);
  }
}
