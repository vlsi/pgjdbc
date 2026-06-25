/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.postgresql.test.codec.CodecTestSupport.CTX;
import static org.postgresql.test.codec.CodecTestSupport.type;

import org.postgresql.core.Oid;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.codec.ByteaCodec;

import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.PropertyChecker;
import org.jetbrains.jetCheck.PropertyFalsified;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

/**
 * jetCheck counterpart to {@link ByteaVariableLengthPropertyTest}, and the most pointed part of the
 * comparison: for variable-length data jetCheck <em>shrinks failing inputs automatically</em>, so
 * the manual {@link Minimizer} the Instancio version needs is unnecessary here.
 */
class ByteaJetCheckTest {

  private static final long SEED = 20260625L;
  private static final int ITERS = 300;
  private static final PgType BYTEA = type("bytea", "bytea", Oid.BYTEA);

  /** bytea round-trips for any byte sequence — jetCheck generates and shrinks the list length. */
  @Test
  @SuppressWarnings("deprecation") // withSeed: see Float8JetCheckTest#check
  void byteaRoundtrips() {
    PropertyChecker.customized().withSeed(SEED).withIterationCount(ITERS)
        .forAll(Generator.listsOf(Generator.integers(0, 255)), ints -> {
          byte[] input = toBytes(ints);
          try {
            byte[] wire = ByteaCodec.INSTANCE.encodeBinary(input, BYTEA, CTX);
            assertArrayEquals(input, (byte[]) ByteaCodec.INSTANCE.decodeBinary(wire, BYTEA, CTX));
            return true;
          } catch (SQLException e) {
            throw new RuntimeException(e);
          }
        });
  }

  /**
   * Demonstrates the headline jetCheck advantage — and its limit. A deliberately broken property
   * ("every element is &lt; 200") is falsified, and jetCheck hands back the <em>minimized</em>
   * counterexample via {@link PropertyFalsified#getBreakingValue()}. It shrinks the random failing
   * list to a <em>single element</em> automatically (the Instancio side reproduces this by hand in
   * {@code Minimizer}/{@code MinimizerTest}). Note that it does not always minimize the element's
   * <em>value</em> to the exact boundary: with this seed it reports {@code [252]}, not {@code [200]}
   * — still a tiny counterexample, but shrinking is "helpful", not "optimal".
   */
  @Test
  @SuppressWarnings({"deprecation", "unchecked"})
  void jetCheckShrinksAutomatically() {
    PropertyFalsified failure = assertThrows(PropertyFalsified.class, () ->
        PropertyChecker.customized().withSeed(SEED).withIterationCount(ITERS)
            .forAll(Generator.listsOf(Generator.integers(0, 255)),
                ints -> ints.stream().allMatch(x -> x < 200)));

    List<Integer> minimal = (List<Integer>) failure.getBreakingValue();
    assertEquals(1, minimal.size(), "shrinks the list to a single element automatically");
    assertTrue(minimal.get(0) >= 200, "the remaining element still violates the property");
  }

  private static byte[] toBytes(List<Integer> ints) {
    byte[] out = new byte[ints.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) (int) ints.get(i);
    }
    return out;
  }
}
