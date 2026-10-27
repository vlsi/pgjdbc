/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.impl;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Prints a thread dump to {@code System.err} when a test or a lifecycle method fails because a
 * connection attempt exceeded {@code loginTimeout}, and rethrows the failure unchanged.
 *
 * <p>The extension is registered through {@code META-INF/services} and relies on
 * {@code junit.jupiter.extensions.autodetection.enabled=true}. It prints at most
 * {@value #MAX_DUMPS} dumps per JVM, since the timeouts of one burst of connections share the
 * cause, and each further dump would repeat the first one.
 *
 * @see LoginTimeoutThreadDump
 */
public class LoginTimeoutThreadDumpExtension
    implements TestExecutionExceptionHandler, LifecycleMethodExecutionExceptionHandler {
  static final int MAX_DUMPS = 3;

  private static final AtomicInteger DUMPS = new AtomicInteger();

  @Override
  public void handleTestExecutionException(ExtensionContext context, Throwable throwable)
      throws Throwable {
    dumpIfLoginTimeout(context, throwable);
    throw throwable;
  }

  @Override
  public void handleBeforeAllMethodExecutionException(ExtensionContext context, Throwable throwable)
      throws Throwable {
    dumpIfLoginTimeout(context, throwable);
    throw throwable;
  }

  @Override
  public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable throwable)
      throws Throwable {
    dumpIfLoginTimeout(context, throwable);
    throw throwable;
  }

  @Override
  public void handleAfterEachMethodExecutionException(ExtensionContext context, Throwable throwable)
      throws Throwable {
    dumpIfLoginTimeout(context, throwable);
    throw throwable;
  }

  @Override
  public void handleAfterAllMethodExecutionException(ExtensionContext context, Throwable throwable)
      throws Throwable {
    dumpIfLoginTimeout(context, throwable);
    throw throwable;
  }

  private static void dumpIfLoginTimeout(ExtensionContext context, Throwable throwable) {
    if (LoginTimeoutThreadDump.findLoginTimeout(throwable) == null) {
      return;
    }
    String testName = context.getUniqueId();
    int dump = DUMPS.incrementAndGet();
    if (dump <= MAX_DUMPS) {
      LoginTimeoutThreadDump.print(testName, System.err);
    } else if (dump == MAX_DUMPS + 1) {
      System.err.println("Connection attempt timed out (loginTimeout) in " + testName
          + "; thread dump skipped, since this JVM already printed " + MAX_DUMPS
          + " dumps, and further login timeouts print no dump");
    }
  }
}
