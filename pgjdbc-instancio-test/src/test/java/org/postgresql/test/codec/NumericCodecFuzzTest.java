/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.codec;

import static org.postgresql.test.codec.CodecTestSupport.CTX;
import static org.postgresql.test.codec.CodecTestSupport.type;

import org.postgresql.core.Oid;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.codec.NumericCodec;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Jazzer (coverage-guided fuzzing) evaluation target — see
 * {@code config/mutation/JAZZER_VS_PITEST_PBT.md}.
 *
 * <p>This is a different kind of tool from PIT and the property libraries: Jazzer mutates raw bytes
 * and uses <em>coverage feedback</em> to evolve inputs that reach new branches, which is exactly the
 * job for the <em>decode</em> side of a codec (parsing untrusted wire data). The oracle here is
 * robustness: decoding arbitrary bytes/text must only ever surface the declared {@link SQLException}
 * — any other {@link Throwable} (AIOOBE, NPE, {@code NegativeArraySize}, …) is a finding — plus a
 * round-trip check when a value does decode.</p>
 *
 * <p>By default ({@code @FuzzTest} regression mode) this just replays any saved corpus, so it is a
 * cheap, deterministic CI test. Set {@code JAZZER_FUZZ=1} to actually fuzz.</p>
 */
class NumericCodecFuzzTest {

  private static final PgType NUMERIC = type("numeric", "numeric", Oid.NUMERIC);

  @FuzzTest(maxDuration = "15s")
  void decodeBinaryIsRobust(FuzzedDataProvider data) throws SQLException {
    // Bounded so a malicious length field cannot make the JVM allocate gigabytes.
    byte[] bytes = data.consumeBytes(40);
    try {
      Object decoded = NumericCodec.INSTANCE.decodeBinary(bytes, NUMERIC, CTX);
      if (decoded instanceof BigDecimal) {
        // Oracle: a value that decodes must re-encode and decode again without error.
        byte[] reencoded = NumericCodec.INSTANCE.encodeBinary(decoded, NUMERIC, CTX);
        NumericCodec.INSTANCE.decodeBinary(reencoded, NUMERIC, CTX);
      }
    } catch (SQLException | IllegalArgumentException declaredOrValidation) {
      // Graceful, declared failure on bad input — acceptable.
    }
  }

  @FuzzTest(maxDuration = "15s")
  void decodeTextIsRobust(FuzzedDataProvider data) throws SQLException {
    String text = data.consumeRemainingAsString();
    if (text == null) {
      return;
    }
    try {
      NumericCodec.INSTANCE.decodeText(text, NUMERIC, CTX);
    } catch (SQLException | IllegalArgumentException declaredOrValidation) {
      // Graceful, declared failure on bad input — acceptable.
    }
  }
}
