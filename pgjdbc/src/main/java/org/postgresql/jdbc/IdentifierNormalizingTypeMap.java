/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.TypeInfo;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * A {@link Map} view over a user-supplied JDBC type map, where a lookup key
 * matches any entry that names the same PostgreSQL type.
 *
 * <p>{@link #get} and {@link #containsKey} try the literal key first; on a
 * miss they resolve the lookup key and each key of the user map to a type OID
 * through {@link TypeInfo#getPgTypeByPgName(String)} (a {@code regtype} cast,
 * cached by the connection) and match on OID. Any two spec-allowed identifier
 * forms of one type therefore reach the same entry: bare, schema-qualified,
 * fully quoted, partial-quoted, mixed-case, or an alias. Mutating methods
 * delegate to the user map.</p>
 *
 * <p>The slow path iterates the user map, typically a handful of entries, and
 * {@code TypeInfo} amortizes each resolution in a per-connection cache, so a
 * missed direct lookup costs at most a few cache hits.</p>
 */
// KeyFor relations bind to the delegate map; the wrapper deliberately accepts
// lookup keys outside that domain (any equivalent identifier form) and forwards
// mutations directly, so KeyFor cannot be enforced at the wrapper boundary.
@SuppressWarnings("keyfor")
final class IdentifierNormalizingTypeMap implements Map<String, Class<?>> {
  private final Map<String, Class<?>> delegate;
  private final TypeInfo typeInfo;

  IdentifierNormalizingTypeMap(Map<String, Class<?>> delegate, TypeInfo typeInfo) {
    this.delegate = delegate;
    this.typeInfo = typeInfo;
  }

  /**
   * Wraps {@code map} so a lookup matches on type identity, or returns
   * {@code map} itself when it is empty or already wrapped.
   *
   * <p>Call this on any type map that arrives from application code: at the
   * JDBC entry point that receives it, and at any driver boundary that passes
   * it on. Wrapping is idempotent, so a second call returns the map unchanged
   * rather than nesting a second wrapper.</p>
   */
  static Map<String, Class<?>> of(Map<String, Class<?>> map, TypeInfo typeInfo) {
    if (map.isEmpty() || map instanceof IdentifierNormalizingTypeMap) {
      return map;
    }
    return new IdentifierNormalizingTypeMap(map, typeInfo);
  }

  @Override
  public int size() {
    return delegate.size();
  }

  @Override
  public boolean isEmpty() {
    return delegate.isEmpty();
  }

  @Override
  public boolean containsKey(@Nullable Object key) {
    return get(key) != null;
  }

  @Override
  public boolean containsValue(@Nullable Object value) {
    // pgjdbc's annotated JDK rejects nulls for containsValue; JDBC type maps
    // never store null values, so the answer is unambiguously false.
    return value != null && delegate.containsValue(value);
  }

  @Override
  public @Nullable Class<?> get(@Nullable Object key) {
    if (key == null) {
      return null;
    }
    Class<?> direct = delegate.get(key);
    if (direct != null) {
      return direct;
    }
    if (delegate.isEmpty() || !(key instanceof String)) {
      return null;
    }
    int lookupOid;
    try {
      lookupOid = typeInfo.getPgTypeByPgName((String) key).getOid();
    } catch (SQLException e) {
      return null;
    }
    for (Map.Entry<String, Class<?>> entry : delegate.entrySet()) {
      try {
        int userOid = typeInfo.getPgTypeByPgName(entry.getKey()).getOid();
        if (userOid == lookupOid) {
          return entry.getValue();
        }
      } catch (SQLException ignored) {
        // Skip entries that fail to resolve — likely garbage keys.
      }
    }
    return null;
  }

  @Override
  public @Nullable Class<?> put(String key, Class<?> value) {
    return delegate.put(key, value);
  }

  @Override
  public @Nullable Class<?> remove(@Nullable Object key) {
    if (key == null) {
      return null;
    }
    return delegate.remove(key);
  }

  @Override
  public void putAll(Map<? extends String, ? extends Class<?>> m) {
    delegate.putAll(m);
  }

  @Override
  public void clear() {
    delegate.clear();
  }

  @Override
  public Set<String> keySet() {
    return delegate.keySet();
  }

  @Override
  public Collection<Class<?>> values() {
    return delegate.values();
  }

  @Override
  public Set<Map.Entry<String, Class<?>>> entrySet() {
    return delegate.entrySet();
  }
}
