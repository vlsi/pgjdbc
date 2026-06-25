/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.codec;

import java.util.Arrays;
import java.util.function.Predicate;

/**
 * A tiny, framework-free shrinker for {@code byte[]} counterexamples.
 *
 * <p>Instancio generates data but does not shrink failing inputs the way a dedicated
 * property-based-testing framework would. For fixed-width scalars that does not matter, but for the
 * variable-length codecs (arrays, composites, {@code bytea}, literal parsing) a random
 * counterexample can be large and hard to read. This helper performs delta-debugging style
 * minimization: it repeatedly removes chunks and then single elements for as long as the input
 * still triggers the failure, yielding a locally minimal counterexample to report.</p>
 *
 * <p>It is deliberateley simple and deterministic so it can be used straight from a JUnit test
 * without pulling in another dependency.</p>
 */
final class Minimizer {

  private Minimizer() {
  }

  /**
   * Returns a locally minimal sub-sequence of {@code failing} for which {@code stillFails} is still
   * {@code true}. {@code stillFails} must be {@code true} for {@code failing} itself.
   */
  static byte[] shrink(byte[] failing, Predicate<byte[]> stillFails) {
    byte[] best = failing;

    // 1. Coarse pass: try to delete progressively smaller contiguous chunks.
    for (int chunk = best.length / 2; chunk >= 1; chunk /= 2) {
      boolean improved = true;
      while (improved) {
        improved = false;
        for (int start = 0; start + chunk <= best.length; start += chunk) {
          byte[] candidate = removeRange(best, start, start + chunk);
          if (stillFails.test(candidate)) {
            best = candidate;
            improved = true;
            break;
          }
        }
      }
    }

    // 2. Fine pass: try to delete each remaining element individually.
    boolean improved = true;
    while (improved && best.length > 0) {
      improved = false;
      for (int i = 0; i < best.length; i++) {
        byte[] candidate = removeRange(best, i, i + 1);
        if (stillFails.test(candidate)) {
          best = candidate;
          improved = true;
          break;
        }
      }
    }
    return best;
  }

  private static byte[] removeRange(byte[] src, int from, int to) {
    byte[] out = new byte[src.length - (to - from)];
    System.arraycopy(src, 0, out, 0, from);
    System.arraycopy(src, to, out, from, src.length - to);
    return out;
  }

  /** Convenience for messages. */
  static String describe(byte[] bytes) {
    return bytes.length + " bytes " + Arrays.toString(bytes);
  }
}
