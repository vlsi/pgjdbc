/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.impl;

import org.postgresql.util.GT;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.PrintStream;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Prints a thread dump of the test JVM when a test fails because a connection attempt exceeded
 * {@code loginTimeout}.
 *
 * <p>When {@code loginTimeout} expires, the driver abandons the connection thread and throws, but the
 * thread keeps running. A dump taken right after the failure shows where that thread waits: in the
 * TCP connect, in the SSL negotiation, in the TLS handshake, or in authentication. Look for threads
 * named {@value #CONNECTION_THREAD_NAME}.
 */
public final class LoginTimeoutThreadDump {
  static final String CONNECTION_THREAD_NAME = "PostgreSQL JDBC driver connection thread";

  private static final String CONNECT_TASK_CLASS = "org.postgresql.Driver$ConnectTask";
  private static final String CONNECT_TASK_METHOD = "getResult";

  private LoginTimeoutThreadDump() {
  }

  /**
   * Finds the exception the driver throws when {@code loginTimeout} expires, searching the causes and
   * the suppressed exceptions of {@code failure}, since a test may wrap the connection failure in an
   * assertion error.
   *
   * @param failure exception a test failed with
   * @return the login timeout exception, or null if the failure was not caused by a login timeout
   */
  public static @Nullable SQLException findLoginTimeout(Throwable failure) {
    // The driver translates the message, so compare it with the translation for the current locale
    String timeoutMessage = GT.tr("Connection attempt timed out.");
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Deque<Throwable> queue = new ArrayDeque<>();
    queue.add(failure);
    Throwable t;
    while ((t = queue.poll()) != null) {
      if (!seen.add(t)) {
        continue;
      }
      if (t instanceof SQLException && timeoutMessage.equals(t.getMessage())
          && thrownByConnectTask(t)) {
        return (SQLException) t;
      }
      Throwable cause = t.getCause();
      if (cause != null) {
        queue.add(cause);
      }
      Collections.addAll(queue, t.getSuppressed());
      if (t instanceof SQLException) {
        SQLException next = ((SQLException) t).getNextException();
        if (next != null) {
          queue.add(next);
        }
      }
    }
    return null;
  }

  private static boolean thrownByConnectTask(Throwable t) {
    StackTraceElement[] stackTrace = t.getStackTrace();
    return stackTrace.length > 0
        && CONNECT_TASK_CLASS.equals(stackTrace[0].getClassName())
        && CONNECT_TASK_METHOD.equals(stackTrace[0].getMethodName());
  }

  /**
   * Prints every thread of the current JVM with its full stack, the monitors it holds, and the
   * lock it waits on.
   *
   * @param testName display name of the failed test, printed in the header
   * @param out where to print the dump
   */
  public static void print(String testName, PrintStream out) {
    ThreadInfo[] threads = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);
    StringBuilder sb = new StringBuilder();
    sb.append("Connection attempt timed out (loginTimeout) in ").append(testName)
        .append(" at ").append(Instant.now())
        .append(". Thread dump of the test JVM follows; threads named \"")
        .append(CONNECTION_THREAD_NAME)
        .append("\" show where the abandoned connection attempts wait.\n");
    for (ThreadInfo thread : threads) {
      appendThread(sb, thread);
    }
    sb.append("End of thread dump for ").append(testName).append('\n');
    // One print call, so the dump is not interleaved with the output of tests running concurrently
    out.print(sb);
    out.flush();
  }

  /**
   * Formats a thread like {@link ThreadInfo#toString()}, which cuts the stack at eight frames.
   */
  private static void appendThread(StringBuilder sb, ThreadInfo thread) {
    sb.append('"').append(thread.getThreadName()).append("\" #").append(thread.getThreadId())
        .append(' ').append(thread.getThreadState());
    LockInfo lock = thread.getLockInfo();
    if (lock != null) {
      sb.append(" on ").append(lock);
      if (thread.getLockOwnerName() != null) {
        sb.append(" owned by \"").append(thread.getLockOwnerName())
            .append("\" #").append(thread.getLockOwnerId());
      }
    }
    sb.append('\n');
    StackTraceElement[] stackTrace = thread.getStackTrace();
    MonitorInfo[] monitors = thread.getLockedMonitors();
    for (int i = 0; i < stackTrace.length; i++) {
      sb.append("\tat ").append(stackTrace[i]).append('\n');
      if (i == 0 && lock != null) {
        sb.append("\t-  waiting on ").append(lock).append('\n');
      }
      for (MonitorInfo monitor : monitors) {
        if (monitor.getLockedStackDepth() == i) {
          sb.append("\t-  locked ").append(monitor).append('\n');
        }
      }
    }
    LockInfo[] synchronizers = thread.getLockedSynchronizers();
    if (synchronizers.length > 0) {
      sb.append("\tLocked synchronizers:\n");
      for (LockInfo synchronizer : synchronizers) {
        sb.append("\t-  ").append(synchronizer).append('\n');
      }
    }
    sb.append('\n');
  }
}
