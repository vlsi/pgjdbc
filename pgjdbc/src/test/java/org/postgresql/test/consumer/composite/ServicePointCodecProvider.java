/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.consumer.composite;

import org.postgresql.api.codec.CodecProvider;
import org.postgresql.api.codec.CodecRegistration;

import java.util.Collections;
import java.util.List;

/** Contributes {@link ServicePointCodec} through the {@link CodecProvider} SPI. */
public final class ServicePointCodecProvider implements CodecProvider {

  @Override
  public List<CodecRegistration> codecs() {
    // A test-owned type name so the registration does not shadow the built-in geometric "point"
    // type globally for every test that touches PGpoint.
    return Collections.singletonList(CodecRegistration.of(
        "consumer_service_loader_service_point", new ServicePointCodec()));
  }
}
