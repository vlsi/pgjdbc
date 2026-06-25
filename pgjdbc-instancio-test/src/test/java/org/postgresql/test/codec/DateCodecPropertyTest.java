/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.postgresql.test.codec.CodecTestSupport.javaTimeCtx;
import static org.postgresql.test.codec.CodecTestSupport.type;

import org.postgresql.core.Oid;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.codec.DateCodec;

import org.instancio.Instancio;
import org.instancio.junit.InstancioExtension;
import org.instancio.junit.Seed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Property test for {@link DateCodec}, the simplest of the temporal codecs and the one that
 * justifies moving {@code TestCodecContext} to {@code testkit}: it reads
 * {@code ctx.prefersJavaTimeForDate()} and the context's timestamp helpers, so it cannot be tested
 * with a {@code null} context. We use the shared connectionless context configured to return
 * {@code java.time} types.
 */
@ExtendWith(InstancioExtension.class)
class DateCodecPropertyTest {

  private static final long SEED = 20260625L;
  private static final int RANDOM_CASES = 200;
  private static final PgType DATE = type("date", "date", Oid.DATE);
  private final CodecContext ctx = javaTimeCtx();

  private List<LocalDate> dates() {
    List<LocalDate> values = new ArrayList<>(List.of(
        LocalDate.of(2000, 1, 1),   // PostgreSQL date epoch
        LocalDate.of(1970, 1, 1),
        LocalDate.of(1900, 1, 1),
        LocalDate.of(2024, 2, 29),  // leap day
        LocalDate.of(2099, 12, 31)));
    // Instancio random dates within a safe range, deterministic via @Seed.
    for (LocalDate d : Instancio.ofList(LocalDate.class).size(RANDOM_CASES).create()) {
      // clamp to a sane range so Date.valueOf round-trips cleanly
      if (d.getYear() >= 1900 && d.getYear() <= 2200) {
        values.add(d);
      }
    }
    return values;
  }

  @Test
  @Seed(SEED)
  void localDateBinaryRoundtrips() throws Exception {
    for (LocalDate d : dates()) {
      byte[] wire = DateCodec.INSTANCE.encodeBinary(d, DATE, ctx);
      assertEquals(d, DateCodec.INSTANCE.decodeBinary(wire, DATE, ctx),
          () -> "date binary roundtrip failed for " + d);
    }
  }

  @Test
  @Seed(SEED)
  void localDateTextRoundtrips() throws Exception {
    for (LocalDate d : dates()) {
      String text = DateCodec.INSTANCE.encodeText(d, DATE, ctx);
      assertEquals(d, DateCodec.INSTANCE.decodeText(text, DATE, ctx),
          () -> "date text roundtrip failed for " + d + " (text=" + text + ")");
    }
  }
}
