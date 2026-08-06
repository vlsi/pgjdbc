/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import java.sql.SQLException;

/**
 * Bounds how deeply codecs may recurse while encoding or decoding one value.
 *
 * <p>Deeply nested or cyclically-referencing composite types would otherwise overflow the stack.
 * {@link #enter()} takes one of {@value #MAX_DEPTH} levels and refuses past that; {@link #exit()}
 * gives the level back.</p>
 *
 * <p>Usage pattern:</p>
 * <pre>{@code
 * CodecDepth.enter();
 * try {
 *     // decode nested type
 * } finally {
 *     CodecDepth.exit();
 * }
 * }</pre>
 *
 * <p>The count is per thread, virtual threads included: a codec operation is short-lived, and every
 * level it takes is released in a {@code finally}. Pairing every {@link #enter()} with an
 * {@link #exit()} that way is what keeps it correct; a level leaked by an exception would shrink
 * the budget of every later codec operation on that thread.</p>
 *
 * @since 42.8.0
 */
public final class CodecDepth {

  /**
   * Maximum allowed nesting depth for encode/decode operations.
   */
  public static final int MAX_DEPTH = 64;

  @SuppressWarnings("type.argument")
  private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

  private CodecDepth() {
    // Utility class
  }

  /**
   * Enters a nested encode/decode operation.
   *
   * <p>Call this at the beginning of any codec operation that may
   * recurse into nested types (arrays, composites, etc.).</p>
   *
   * @throws SQLException if maximum nesting depth is exceeded
   */
  public static void enter() throws SQLException {
    int depth = DEPTH.get() + 1;
    if (depth > MAX_DEPTH) {
      throw Exceptions.maxNestingDepthExceeded(MAX_DEPTH);
    }
    DEPTH.set(depth);
  }

  /**
   * Exits a nested encode/decode operation.
   */
  public static void exit() {
    int depth = DEPTH.get();
    if (depth > 0) {
      DEPTH.set(depth - 1);
    }
  }

  /**
   * Clears the depth counter for the current thread.
   *
   * <p>This should be called at the end of top-level operations
   * to clean up ThreadLocal state, especially important for thread pools.</p>
   */
  public static void clear() {
    DEPTH.remove();
  }

  /**
   * Returns the current nesting depth.
   *
   * <p>Primarily useful for debugging and testing.</p>
   *
   * @return the current depth (0 if not inside any codec operation)
   */
  public static int current() {
    return DEPTH.get();
  }
}
