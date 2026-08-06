/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Binds a {@link Codec} to the PostgreSQL type names it handles.
 *
 * <p>The names live here rather than on the codec because they are a property of the registration,
 * not of the conversion: the same codec serves every range type, every domain, every composite. A
 * codec therefore says only how to convert a value; what it is registered under is decided by
 * whoever registers it.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public final class CodecRegistration {

  private final String localTypeName;
  private final List<String> aliases;
  private final Codec codec;

  private CodecRegistration(String localTypeName, List<String> aliases, Codec codec) {
    if (localTypeName.isEmpty()) {
      throw new IllegalArgumentException("typeName must not be empty");
    }
    this.localTypeName = localTypeName;
    this.aliases = aliases;
    this.codec = codec;
  }

  /**
   * Binds {@code codec} to {@code typeName} and any further names it should also answer to.
   *
   * @param typeName the primary PostgreSQL type name, unqualified (for example {@code "int4"})
   * @param codec the codec handling that type
   * @param aliases further names resolving to the same codec (for example {@code "integer"})
   * @return the registration
   * @throws IllegalArgumentException if {@code typeName} is empty
   */
  public static CodecRegistration of(String typeName, Codec codec, String... aliases) {
    List<String> copy = aliases.length == 0
        ? Collections.<String>emptyList()
        : Collections.unmodifiableList(new ArrayList<>(Arrays.asList(aliases)));
    return new CodecRegistration(typeName, copy, codec);
  }

  /**
   * Returns the unqualified PostgreSQL type name this registration matches.
   *
   * <p>The name is {@code pg_type.typname}, without its schema. A registration therefore applies to
   * types with this local name in any schema, such as an {@code hstore} extension installed outside
   * {@code public}. It cannot distinguish two types with the same local name in different schemas.</p>
   *
   * @return the primary unqualified PostgreSQL type name
   */
  public String getLocalTypeName() {
    return localTypeName;
  }

  /**
   * Returns the further names resolving to the same codec.
   *
   * @return the aliases, possibly empty, never null
   */
  public List<String> getAliases() {
    return aliases;
  }

  /**
   * Returns the registered codec.
   *
   * @return the codec
   */
  public Codec getCodec() {
    return codec;
  }

  @Override
  public String toString() {
    return localTypeName + (aliases.isEmpty() ? "" : " " + aliases) + " -> " + codec.getClass().getTypeName();
  }
}
