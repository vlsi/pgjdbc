/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.jdbc.PgField;
import org.postgresql.jdbc.PgType;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link TypmodTypeDescriptor} overrides the applied typmod and forwards everything else by hand,
 * one method per property. Adding a {@link TypeDescriptor} method with a {@code default} body would
 * not break that compilation — the wrapper would simply stop forwarding it and start answering from
 * the interface default, which is a silent behavior change.
 *
 * <p>So this test enumerates the interface reflectively rather than naming methods: a method added
 * to {@code TypeDescriptor} joins the comparison on its own. The three methods that intentionally
 * do <em>not</em> delegate are listed in {@link #INTENTIONALLY_NOT_DELEGATED} and asserted
 * separately.</p>
 *
 * <p>The delegate is a real {@link PgType} rather than a mock. The interface carries derived
 * defaults — {@link TypeDescriptor#isArray()} reads {@code typcategory},
 * {@link TypeDescriptor#isComposite()} and friends read {@code typtype} — which the wrapper
 * correctly leaves alone. A mock answering every method with its own stub value would report a
 * different {@code isComposite()} than the wrapper computes, failing for no reason; a real type
 * computes them identically on both sides. Every slot of {@link #distinctiveDelegate()} holds a
 * distinctive value, so a wrapper that answered from a hardcoded constant or an interface default
 * would not match.</p>
 */
class TypmodTypeDescriptorDelegationTest {

  /** The modifier the wrapper stamps on; deliberately unlike the delegate's own typtypmod. */
  private static final int STAMPED_TYPMOD = 786_436;

  /**
   * Methods whose whole purpose is to differ from the delegate. Each carries its own assertion in
   * this class, so removing a name from this set without adding an assertion loses coverage rather
   * than silently passing.
   */
  private static final Set<String> INTENTIONALLY_NOT_DELEGATED =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
          "getAppliedTypmod", "withTypmod", "withAttributes")));

  /**
   * A descriptor with a distinctive value in every slot. {@code typtype='c'} and
   * {@code typcategory='A'} make the derived predicates mixed rather than uniformly false, so a
   * wrapper that hardcoded {@code false} would be caught.
   */
  private static PgType distinctiveDelegate() {
    List<PgField> attributes = new ArrayList<>();
    attributes.add(new PgField("probe_attr", 23, 1, 5));
    return new PgType(
        TypeName.of("probe_ns", "probe_type"),
        "probe_ns.probe_type",
        90_211,      // oid
        'c',         // typtype: composite, so isComposite() is true and its siblings are false
        'A',         // typcategory: array, so isArray() is true
        90_212,      // typtypmod, distinct from the stamped applied typmod
        90_213,      // typelem
        90_214,      // arrayOid
        90_215,      // typbasetype
        ';',         // delimiter, not the ',' default
        attributes)
        .withRangeSubtype(90_216)
        .withMultirangeRange(90_217);
  }

  /**
   * Every method that is not on the exception list must return what the delegate returns.
   *
   * <p>The wrapper is constructed directly: {@link PgType#withTypmod(int)} returns an in-place copy,
   * so going through the public lever would never produce a {@code TypmodTypeDescriptor}. This
   * wrapper is the fallback for descriptors the driver did not build, and that is what is under
   * test.</p>
   */
  @Test
  void forwardsEveryPropertyToTheDelegate() throws Exception {
    PgType delegate = distinctiveDelegate();
    TypeDescriptor wrapper = new TypmodTypeDescriptor(delegate, STAMPED_TYPMOD);

    int compared = 0;
    for (Method method : TypeDescriptor.class.getMethods()) {
      if (INTENTIONALLY_NOT_DELEGATED.contains(method.getName())) {
        continue;
      }
      // A future method taking arguments cannot be invoked blindly. Fail loudly so whoever adds one
      // extends this test, rather than letting it drop out of the comparison unnoticed.
      assertEquals(0, method.getParameterCount(),
          () -> "TypeDescriptor." + method.getName() + " takes arguments; teach this test how to "
              + "call it, or add it to INTENTIONALLY_NOT_DELEGATED with its own assertion");

      Object expected = method.invoke(delegate);
      Object actual = method.invoke(wrapper);
      assertEquals(expected, actual,
          () -> "TypmodTypeDescriptor." + method.getName() + " must delegate");
      compared++;
    }

    // Guards against the loop silently comparing nothing, e.g. if the interface were renamed away.
    assertTrue(compared >= 15, "expected the whole descriptor surface to be compared, got " + compared);
  }

  /** Every name on the exception list must still exist, so a rename cannot mute the assertions. */
  @Test
  void exceptionListMatchesTheInterface() {
    Set<String> declared = new HashSet<>();
    for (Method method : TypeDescriptor.class.getMethods()) {
      declared.add(method.getName());
    }
    for (String name : INTENTIONALLY_NOT_DELEGATED) {
      assertTrue(declared.contains(name),
          () -> "INTENTIONALLY_NOT_DELEGATED names " + name + ", which TypeDescriptor no longer "
              + "declares; drop it or point it at the new name");
    }
  }

  @Test
  void reportsTheStampedTypmodInsteadOfTheDelegates() {
    PgType delegate = distinctiveDelegate();
    TypeDescriptor wrapper = new TypmodTypeDescriptor(delegate, STAMPED_TYPMOD);

    assertEquals(STAMPED_TYPMOD, wrapper.getAppliedTypmod(), "the wrapper reports its own modifier");
    assertEquals(-1, delegate.getAppliedTypmod(), "the delegate is left unchanged");
    assertEquals(delegate.getCatalogTypmod(), wrapper.getCatalogTypmod(),
        "the catalog modifier is a different property and still delegates");
  }

  @Test
  void restampsRatherThanNestsWrappers() {
    TypeDescriptor wrapper = new TypmodTypeDescriptor(distinctiveDelegate(), STAMPED_TYPMOD);

    TypeDescriptor same = wrapper.withTypmod(STAMPED_TYPMOD);
    assertSame(wrapper, same, "restamping the same modifier returns the same instance");

    TypeDescriptor restamped = wrapper.withTypmod(7);
    assertEquals(7, restamped.getAppliedTypmod(), "restamping replaces the modifier");
    assertFalse(restamped instanceof TypmodTypeDescriptor,
        "restamping unwraps to the delegate's own copy rather than nesting another wrapper");
  }

  @Test
  void keepsTheStampedTypmodWhenAttributesAreReplaced() {
    TypeDescriptor wrapper = new TypmodTypeDescriptor(distinctiveDelegate(), STAMPED_TYPMOD);
    List<PgField> synthesized =
        Collections.singletonList(new PgField("from_wire", 25, 1, -1));

    TypeDescriptor reattributed = wrapper.withAttributes(synthesized);

    assertEquals(synthesized, reattributed.getAttributes(), "the new attributes are reported");
    assertEquals(STAMPED_TYPMOD, reattributed.getAppliedTypmod(),
        "the stamped modifier survives re-attribution, so both views hold at once");
  }
}
