/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import java.util.List;

/**
 * A {@link TypeDescriptor} that reports a supplied {@link #getAppliedTypmod() typmod} and delegates every
 * other property to a wrapped descriptor.
 *
 * <p>This is the fallback the default {@link TypeDescriptor#withTypmod(int)} returns for a descriptor
 * that does not provide its own copy-with-typmod. The driver's own {@code PgType} overrides
 * {@code withTypmod} with an in-place copy, so this wrapper is used only for third-party descriptors,
 * such as one registered through the offline codec builder.</p>
 */
final class TypmodTypeDescriptor implements TypeDescriptor {

  private final TypeDescriptor delegate;
  private final int typmod;

  TypmodTypeDescriptor(TypeDescriptor delegate, int typmod) {
    this.delegate = delegate;
    this.typmod = typmod;
  }

  @Override
  public int getAppliedTypmod() {
    return typmod;
  }

  @Override
  public TypeDescriptor withTypmod(int typmod) {
    // Restamp the original descriptor rather than nest another wrapper around this one.
    return typmod == this.typmod ? this : delegate.withTypmod(typmod);
  }

  @Override
  public int getOid() {
    return delegate.getOid();
  }

  @Override
  public TypeName getName() {
    return delegate.getName();
  }

  @Override
  public String getFormattedName() {
    return delegate.getFormattedName();
  }

  @Override
  public int getCatalogTypmod() {
    return delegate.getCatalogTypmod();
  }

  @Override
  public int getTypelem() {
    return delegate.getTypelem();
  }

  @Override
  public int getArrayOid() {
    return delegate.getArrayOid();
  }

  @Override
  public int getTypbasetype() {
    return delegate.getTypbasetype();
  }

  @Override
  public int getRangeSubtype() {
    return delegate.getRangeSubtype();
  }

  @Override
  public int getMultirangeRange() {
    return delegate.getMultirangeRange();
  }

  @Override
  public char getTyptype() {
    return delegate.getTyptype();
  }

  @Override
  public char getTypcategory() {
    return delegate.getTypcategory();
  }

  @Override
  public char getDelimiter() {
    return delegate.getDelimiter();
  }

  @Override
  public List<? extends CompositeAttribute> getAttributes() {
    return delegate.getAttributes();
  }

  @Override
  public TypeDescriptor withAttributes(List<? extends CompositeAttribute> attributes) {
    // Restamp the typmod onto the re-attributed delegate, so both views survive.
    return delegate.withAttributes(attributes).withTypmod(typmod);
  }

  @Override
  public String toString() {
    return delegate + " typmod=" + typmod;
  }
}
