/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Guards the naming rules documented in {@code org.postgresql.api.codec}'s package javadoc, so that
 * one piece of state is spelled one way on both sides of the API. The rules are cheap to keep now
 * and impossible to apply once the codec API is frozen, which is why they are a test rather than a
 * review habit.
 *
 * <p>Only the mechanically checkable half lives here. Whether a verb reads as a sentence about the
 * receiver ({@code usesIntegerDateTimes} rather than {@code integerDateTimesFlag}) still needs a
 * human.</p>
 */
class ApiNamingArchTest {

  private static final JavaClasses API_CLASSES = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("org.postgresql.api..");

  /**
   * Fluent setters that deliberately have no accessor, as {@code <declaring simple name>.<method>}.
   * The context publishes a resolved view of this state instead of handing back what was registered,
   * so there is no accessor for the setter to mirror.
   */
  private static final Set<String> SETTERS_WITHOUT_ACCESSOR = new HashSet<>(Arrays.asList(
      // CodecContext exposes resolveCodec(int)/resolveType(int), not the lookup or the raw map.
      "CodecContextBuilder.codecLookup",
      "CodecContextBuilder.type",
      "CodecContextBuilder.types"));

  @Test
  void booleanQueriesAreNotNamedGet() {
    ArchRule rule = noMethods()
        .that().arePublic()
        .and().haveRawReturnType(boolean.class)
        .should().haveNameMatching("get[A-Z].*")
        .because(
            "a boolean query in the codec API reads as a sentence about the receiver -- isArray(), "
                + "usesIntegerDateTimes(), convertsBooleanToNumeric(), canDecodeText() -- so that a "
                + "builder setter can repeat the name verbatim. getX() breaks that: the setter would "
                + "have to drop the prefix and the two sides would no longer be greppable as one name");
    rule.check(API_CLASSES);
  }

  @Test
  void fluentSettersRepeatTheirAccessorName() {
    List<String> violations = new ArrayList<>();
    List<String> checked = new ArrayList<>();
    for (JavaClass builder : API_CLASSES) {
      JavaClass built = buildReturnType(builder);
      if (built == null) {
        continue;
      }
      for (JavaMethod setter : builder.getMethods()) {
        if (!isFluentSetter(builder, setter)
            || SETTERS_WITHOUT_ACCESSOR.contains(builder.getSimpleName() + "." + setter.getName())) {
          continue;
        }
        checked.add(builder.getSimpleName() + "." + setter.getName());
        boolean setsBoolean = setter.getRawParameterTypes().get(0).isEquivalentTo(boolean.class);
        String expected = setsBoolean ? setter.getName() : getterNameFor(setter.getName());
        if (!declaresAccessor(built, expected)) {
          violations.add(builder.getSimpleName() + "." + setter.getName() + "(...) has no "
              + built.getSimpleName() + "." + expected + "() to mirror");
        }
      }
    }
    // A rule that silently matches nothing passes forever. Pin the setters it is known to reach, so
    // that a change to how a builder is recognised fails here rather than going quiet.
    Collections.sort(checked);
    assertEquals(Arrays.asList(
        "Builder.prefersLocalDate",
        "Builder.prefersLocalDateTime",
        "Builder.prefersLocalTime",
        "Builder.prefersOffsetDateTime",
        "Builder.prefersOffsetTime",
        "CodecContextBuilder.charset",
        "CodecContextBuilder.clientTimeZone",
        "CodecContextBuilder.convertsBooleanToNumeric",
        "CodecContextBuilder.intervalStyle",
        "CodecContextBuilder.javaTimePreferences",
        "CodecContextBuilder.usesIntegerDateTimes"), checked,
        "The setters this rule reaches. Add a builder or a setter and this list grows -- update it. "
            + "If it shrinks unexpectedly, the rule stopped recognising a builder and is no longer "
            + "guarding it.");
    assertEquals(Collections.emptyList(), violations,
        "Every fluent setter names the state exactly as the built type reports it: identically for a "
            + "boolean (usesIntegerDateTimes(boolean) sets what usesIntegerDateTimes() reports), and "
            + "without the get prefix otherwise (charset(Charset) sets what getCharset() reports). "
            + "Rename one side to match the other, or -- if the built type deliberately publishes "
            + "something else instead -- add the setter to SETTERS_WITHOUT_ACCESSOR with the reason.");
  }

  /**
   * The type {@code builder} builds, or null when it is not a builder. A public no-arg
   * {@code build()} is what makes a type one, which also names the type its setters must mirror.
   */
  private static @Nullable JavaClass buildReturnType(JavaClass builder) {
    for (JavaMethod method : builder.getMethods()) {
      if ("build".equals(method.getName())
          && isPublicApi(method)
          && method.getRawParameterTypes().isEmpty()) {
        return method.getRawReturnType();
      }
    }
    return null;
  }

  /** A single-argument method returning the builder itself, which is how a fluent setter looks. */
  private static boolean isFluentSetter(JavaClass builder, JavaMethod method) {
    return isPublicApi(method)
        && method.getRawParameterTypes().size() == 1
        && method.getRawReturnType().getName().equals(builder.getName());
  }

  private static boolean declaresAccessor(JavaClass type, String name) {
    for (JavaMethod method : type.getMethods()) {
      if (name.equals(method.getName())
          && isPublicApi(method)
          && method.getRawParameterTypes().isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /** Public and written by hand: bridge and synthetic methods are the compiler's, not the API's. */
  private static boolean isPublicApi(JavaMethod method) {
    Set<JavaModifier> modifiers = method.getModifiers();
    return (modifiers.contains(JavaModifier.PUBLIC) || method.getOwner().isInterface())
        && !modifiers.contains(JavaModifier.BRIDGE)
        && !modifiers.contains(JavaModifier.SYNTHETIC);
  }

  private static String getterNameFor(String setterName) {
    return "get" + Character.toUpperCase(setterName.charAt(0)) + setterName.substring(1);
  }
}
