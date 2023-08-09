// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.objc;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.RequiresOptions;
import com.google.devtools.build.lib.analysis.starlark.annotations.StarlarkConfigurationField;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.BuiltinRestriction;
import com.google.devtools.build.lib.rules.cpp.CcModule;
import com.google.devtools.build.lib.starlarkbuildapi.apple.J2ObjcConfigurationApi;
import java.util.Collections;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkThread;

/**
 * A J2ObjC transpiler configuration fragment containing J2ObjC translation flags. This
 * configuration fragment is used by Java rules that can be transpiled (specifically, J2ObjCAspects
 * thereof).
 */
@Immutable
@RequiresOptions(options = {J2ObjcCommandLineOptions.class})
public class J2ObjcConfiguration extends Fragment implements J2ObjcConfigurationApi {
  /**
   * Always-on flags for J2ObjC translation. These flags are always used when invoking the J2ObjC
   * transpiler, and cannot be overridden by user-specified flags in {@link
   * J2ObjcCommandLineOptions}. See https://j2objc.org/reference/j2objc.html for flag documentation.
   */
  private static final ImmutableList<String> J2OBJC_ALWAYS_ON_TRANSLATION_FLAGS =
      ImmutableList.of(
          "-encoding", "UTF-8", "--doc-comments", "-XcombineJars", "-XDinjectLogSites=true");

  /**
   * Default flags for J2ObjC translation. These flags are used by default when invoking the J2ObjC
   * transpiler, but can be overridden by user-specified flags in {@link J2ObjcCommandLineOptions}.
   * See https://j2objc.org/reference/j2objc.html for flag documentation.
   */
  private static final ImmutableList<String> J2OBJC_DEFAULT_TRANSLATION_FLAGS =
      ImmutableList.of("-g");

  /** The j2objc flag to generate ARC-compatible code. */
  private static final String J2OBJC_USE_ARC_FLAG = "-use-arc";

  /**
   * Disallowed flags for J2ObjC translation. See https://j2objc.org/reference/j2objc.html for flag
   * documentation.
   */
  static final ImmutableList<String> J2OBJC_BLACKLISTED_TRANSLATION_FLAGS =
      ImmutableList.of("--prefixes", "--prefix", "-x");

  static final String INVALID_TRANSLATION_FLAGS_MSG_TEMPLATE =
      "J2Objc translation flags: %s not supported. Unsupported flags are: %s";

  private final ImmutableList<String> translationFlags;
  private final boolean removeDeadCode;
  private final boolean experimentalJ2ObjcHeaderMap;
  private final boolean experimentalShorterHeaderPath;
  private final boolean j2objcLibraryMigration;
  @Nullable private final Label deadCodeReport;

  public J2ObjcConfiguration(BuildOptions buildOptions) {
    J2ObjcCommandLineOptions j2ObjcOptions = buildOptions.get(J2ObjcCommandLineOptions.class);
    this.translationFlags =
        ImmutableList.<String>builder()
            .addAll(J2OBJC_DEFAULT_TRANSLATION_FLAGS)
            .addAll(j2ObjcOptions.translationFlags)
            .addAll(J2OBJC_ALWAYS_ON_TRANSLATION_FLAGS)
            .build();
    this.removeDeadCode = j2ObjcOptions.removeDeadCode;
    this.experimentalJ2ObjcHeaderMap = j2ObjcOptions.experimentalJ2ObjcHeaderMap;
    this.experimentalShorterHeaderPath = j2ObjcOptions.experimentalShorterHeaderPath;
    this.deadCodeReport = j2ObjcOptions.deadCodeReport;
    this.j2objcLibraryMigration = j2ObjcOptions.j2objcLibraryMigration;
  }

  /**
   * Returns the translation flags used to invoke the J2ObjC transpiler. The returned flags contain
   * the default flags from {@link #J2OBJC_DEFAULT_TRANSLATION_FLAGS}, user-specified flags from
   * {@link J2ObjcCommandLineOptions}, and always-on flags from {@link
   * #J2OBJC_ALWAYS_ON_TRANSLATION_FLAGS}. The set of disallowed flags can be found at
   * {@link #J2OBJC_BLACKLISTED_TRANSLATION_FLAGS}.
   */
  @Override
  public ImmutableList<String> getTranslationFlags() {
    return translationFlags;
  }

  /**
   * Returns the label of the dead code report generated by ProGuard for J2ObjC to eliminate dead
   * code. The dead Java code in the report will not be translated to Objective-C code.
   *
   * <p>Returns null if no such report was requested.
   */
  @Nullable
  @StarlarkConfigurationField(
      name = "dead_code_report",
      doc =
          "The label of the dead code report generated by ProGuard for dead code elimination, "
              + "or <code>None</code> if no such report was requested.",
      defaultLabel = "")
  public Label deadCodeReport() {
    return deadCodeReport;
  }

  /**
   * Returns whether to perform J2ObjC dead code removal. If true, the list of entry classes will be
   * collected transitively throuh "entry_classes" attribute on j2objc_library and used as entry
   * points to perform dead code analysis. Unused classes will then be removed from the final ObjC
   * app bundle.
   */
  public boolean removeDeadCode() {
    return removeDeadCode;
  }

  @Override
  public boolean getRemoveDeadCodeForStarlark(StarlarkThread thread) throws EvalException {
    BuiltinRestriction.failIfCalledOutsideBuiltins(thread);
    return removeDeadCode;
  }

  /**
   * Returns whether to generate J2ObjC header map in a separate action in parallel of the J2ObjC
   * transpilation action.
   */
  public boolean experimentalJ2ObjcHeaderMap() {
    return experimentalJ2ObjcHeaderMap;
  }

  @Override
  public boolean getExperimentalJ2ObjcHeaderMapForStarlark(StarlarkThread thread)
      throws EvalException {
    BuiltinRestriction.failIfCalledOutsideBuiltins(thread);
    return experimentalJ2ObjcHeaderMap;
  }

  /**
   * Returns whether to use a shorter path for generated header files.
   */
  public boolean experimentalShorterHeaderPath() {
    return experimentalShorterHeaderPath;
  }

  @Override
  public boolean experimentalShorterHeaderPathforStarlark(StarlarkThread thread)
      throws EvalException {
    BuiltinRestriction.failIfCalledOutsideBuiltins(thread);
    return experimentalShorterHeaderPath;
  }

  /** Returns whether objc_library should build generated files using ARC (-fobjc-arc). */
  public boolean compileWithARC() {
    return translationFlags.contains(J2OBJC_USE_ARC_FLAG);
  }

  public boolean j2objcLibraryMigration() {
    return j2objcLibraryMigration;
  }

  @Override
  public boolean j2objcLibraryMigrationForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return j2objcLibraryMigration();
  }

  @Override
  public void reportInvalidOptions(EventHandler reporter, BuildOptions buildOptions) {
    if (!Collections.disjoint(translationFlags, J2OBJC_BLACKLISTED_TRANSLATION_FLAGS)) {
      String errorMsg = String.format(INVALID_TRANSLATION_FLAGS_MSG_TEMPLATE,
          Joiner.on(",").join(translationFlags),
          Joiner.on(",").join(J2OBJC_BLACKLISTED_TRANSLATION_FLAGS));
      reporter.handle(Event.error(errorMsg));
    }
  }
}
