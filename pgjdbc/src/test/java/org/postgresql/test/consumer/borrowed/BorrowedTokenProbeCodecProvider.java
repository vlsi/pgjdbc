/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.consumer.borrowed;

import org.postgresql.api.codec.CodecProvider;
import org.postgresql.api.codec.CodecRegistration;

import java.util.Collections;
import java.util.List;

/** Contributes {@link BorrowedTokenProbeCodec} through the {@link CodecProvider} SPI. */
public final class BorrowedTokenProbeCodecProvider implements CodecProvider {

  @Override
  public List<CodecRegistration> codecs() {
    return Collections.singletonList(CodecRegistration.of(
        BorrowedTokenProbeCodec.TYPE_NAME, new BorrowedTokenProbeCodec()));
  }
}
