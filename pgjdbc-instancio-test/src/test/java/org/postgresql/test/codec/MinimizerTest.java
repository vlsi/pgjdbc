/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Demonstrates that {@link Minimizer} reduces a large failing input to a minimal counterexample,
 * using a synthetic "fails if it contains a zero byte" predicate. This is the mechanism the
 * variable-length codec property tests rely on to keep counterexamples readable without a
 * full property-based-testing framework's built-in shrinking.
 */
class MinimizerTest {

  private static boolean containsZero(byte[] bytes) {
    for (byte b : bytes) {
      if (b == 0) {
        return true;
      }
    }
    return false;
  }

  @Test
  void shrinksLargeInputToTheSingleOffendingByte() {
    byte[] big = new byte[64];
    for (int i = 0; i < big.length; i++) {
      big[i] = (byte) (i + 1); // non-zero filler
    }
    big[40] = 0; // one offending byte buried in a large array

    assertTrue(containsZero(big));
    byte[] minimal = Minimizer.shrink(big, MinimizerTest::containsZero);
    assertArrayEquals(new byte[]{0}, minimal,
        "minimizer should reduce to the single offending byte");
  }

  @Test
  void keepsAnAlreadyMinimalCounterexample() {
    byte[] one = {0};
    assertArrayEquals(new byte[]{0}, Minimizer.shrink(one, MinimizerTest::containsZero));
  }
}
