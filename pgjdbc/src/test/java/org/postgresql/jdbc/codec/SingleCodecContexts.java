/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.CodecRegistry;
import org.postgresql.jdbc.OfflineCodecs;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Builds a real, connectionless {@link CodecContext} whose registry resolves every type to one
 * codec, so a test can drive {@link org.postgresql.api.codec.Codecs} against a specific codec.
 *
 * <p>The substitution belongs in the registry, not in a hand-written {@code CodecContext}: resolving
 * a codec is what a registry does, and the codec under test then runs against the same driver
 * context it gets in production rather than a stand-in that only implements the methods the test
 * happened to need.</p>
 */
final class SingleCodecContexts {

  private SingleCodecContexts() {
  }

  /**
   * Returns a connectionless context resolving every OID and name to {@code codec}.
   *
   * @param codec the codec every lookup returns
   * @return the context
   */
  static CodecContext of(Codec codec) {
    return OfflineCodecs.builder().registry(new SingleCodecRegistry(codec)).build();
  }

  private static final class SingleCodecRegistry extends CodecRegistry {
    private final Codec codec;

    SingleCodecRegistry(Codec codec) {
      this.codec = codec;
    }

    @Override
    public Codec getByOid(int oid, @Nullable TypeDescriptor pgType) {
      // The superclass constructor registers the built-ins and may resolve through this method
      // before the field is assigned; defer to it until then.
      Codec single = codec;
      return single != null ? single : super.getByOid(oid, pgType);
    }

    @Override
    public @Nullable Codec getByLocalName(String localTypeName) {
      Codec single = codec;
      return single != null ? single : super.getByLocalName(localTypeName);
    }
  }
}
