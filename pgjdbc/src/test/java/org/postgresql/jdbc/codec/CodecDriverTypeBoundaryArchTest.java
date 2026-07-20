/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

/**
 * The built-in codecs must reach the driver only through the codec SPI, never through its JDBC
 * implementation types.
 *
 * <p>This replaces the runtime substitute-context test that used to pin the same rule. That test
 * worked by handing the codecs a {@code CodecContext} that was deliberately not the driver's own, so
 * a downcast blew up. Once {@code CodecContext} became a driver-owned abstract class such a
 * substitute stopped being expressible — and the rule is better checked directly anyway: this
 * inspects every reference, not only the ones one hand-written context happened to exercise.</p>
 *
 * <p>The remaining references are listed below one by one, each with the capability the SPI is
 * missing. The list is asserted for equality, not containment, so removing a reference without
 * striking it here fails too and the list can only shrink deliberately.</p>
 *
 * <p>Scope: this walks member accesses — calls and field reads. A bare class literal such as
 * {@code targetClass == PgStruct.class} is not an access and does not appear; those are removed by
 * the same cleanup but are not what this rule guards against.</p>
 */
class CodecDriverTypeBoundaryArchTest {

  private static final String CODEC_PACKAGE = "org.postgresql.jdbc.codec";
  private static final String DRIVER_PACKAGE = "org.postgresql.jdbc";

  /**
   * Driver classes the codec layer may use. These live in {@code org.postgresql.jdbc} for historical
   * reasons but are codec infrastructure, not JDBC value types: they expose no {@code Connection},
   * {@code ResultSet}, {@code Struct} or {@code Array} semantics a codec could depend on.
   */
  private static final Set<String> ALLOWED_DRIVER_TYPES = new TreeSet<>(Arrays.asList(
      // Recursion depth guard shared by the delegating codecs.
      DRIVER_PACKAGE + ".CodecDepth",
      // The temporal codecs themselves; they simply sit one package up.
      DRIVER_PACKAGE + ".TemporalCodecs",
      // Boolean literal parsing shared with the JDBC getters.
      DRIVER_PACKAGE + ".BooleanTypeUtil"));

  /**
   * Known references still crossing the boundary, as {@code Origin#member -> Target}.
   *
   * <p>Each entry names the SPI capability whose absence forces it. They are tolerated so the rule
   * can guard the boundary now rather than after the cleanup; a new reference outside this list
   * fails the test.</p>
   */
  private static final Set<String> ACCEPTED = new TreeSet<>(Arrays.asList(
      // Needs: a way to build a CompositeAttribute without the driver's PgField, for the anonymous
      // RECORD pseudo-type whose attributes the catalog does not carry.
      "CompositeCodec#structTypeFor -> PgField",
      // Needs: a way to ask a java.sql.Struct for the TypeDescriptor it carries, so a nested
      // anonymous record can be decoded against its own synthesized attributes.
      "CompositeCodec#fieldTypeFor -> PgStruct",
      "CompositeCodec#fieldTypeFor -> PgType",
      // Needs: a way to ask a java.sql.Array whether it already holds an encodable binary payload,
      // so the bind path can reuse it instead of re-encoding. This is an encode-side optimization,
      // not a decode downcast, and needs a new capability rather than a cleanup.
      "ArrayCodec#encodeBinary -> PgArray",
      "ArrayCodec#canEncodeBinary -> PgArray",
      "MultiDimArraySupport#unwrapArrayValue -> PgArray"));

  @Test
  void builtInCodecsReachTheDriverOnlyThroughTheSpi() {
    JavaClasses codecs = new ClassFileImporter()
        .withImportOption(new ImportOption.DoNotIncludeTests())
        .importPackages(CODEC_PACKAGE);

    Set<String> found = new TreeSet<>();
    for (JavaClass origin : codecs) {
      for (JavaCodeUnit unit : origin.getCodeUnits()) {
        unit.getAccessesFromSelf().stream()
            .map(access -> access.getTargetOwner())
            .filter(CodecDriverTypeBoundaryArchTest::isDriverImplementationType)
            .forEach(target -> found.add(
                origin.getSimpleName() + "#" + unit.getName() + " -> " + target.getSimpleName()));
      }
    }

    assertEquals(ACCEPTED, found,
        "Built-in codecs must reach the driver through the codec SPI. A new entry means a codec "
            + "took a shortcut to a JDBC implementation type; a missing one means a shortcut was "
            + "removed and should be struck from ACCEPTED.");
  }

  private static boolean isDriverImplementationType(JavaClass type) {
    return DRIVER_PACKAGE.equals(type.getPackageName())
        && !ALLOWED_DRIVER_TYPES.contains(type.getFullName());
  }
}
