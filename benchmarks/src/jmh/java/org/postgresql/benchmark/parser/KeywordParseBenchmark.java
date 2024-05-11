/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.parser;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

@Fork(value = 3, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class KeywordParseBenchmark {

  @Param({"true", "false"})
  boolean ascii;

  private String inputSQL;
  private char[] sql;
  int offset;

  @Setup
  public void setup() {
    inputSQL = " select * from generate_series(1, 2) as g(x)";
    if (!ascii) {
      inputSQL = inputSQL + " /* 😊 */";
    }
    sql = inputSQL.toCharArray();
    offset = 1;
  }

  @Benchmark
  public boolean charOr32() {
    return parseSelectKeyword(sql, offset);
  }

  @Benchmark
  public boolean regionMatches() {
    return inputSQL.regionMatches(true, offset, "select", 0, 6);
  }

  public static boolean parseSelectKeyword(final char[] query, int offset) {
    if (query.length < (offset + 6)) {
      return false;
    }

    return (query[offset] | 32) == 's'
        && (query[offset + 1] | 32) == 'e'
        && (query[offset + 2] | 32) == 'l'
        && (query[offset + 3] | 32) == 'e'
        && (query[offset + 4] | 32) == 'c'
        && (query[offset + 5] | 32) == 't';
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(KeywordParseBenchmark.class.getSimpleName())
        .addProfiler(GCProfiler.class)
        //.addProfiler(FlightRecorderProfiler.class)
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }
}
