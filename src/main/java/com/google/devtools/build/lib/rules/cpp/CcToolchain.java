// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.cpp;

import static com.google.devtools.build.lib.packages.BuildType.LABEL;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.TemplateVariableInfo;
import com.google.devtools.build.lib.analysis.platform.ToolchainInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.rules.apple.XcodeConfig;
import com.google.devtools.build.lib.rules.apple.XcodeConfigInfo;
import com.google.devtools.build.lib.rules.apple.XcodeConfigRule;
import java.util.HashMap;
import javax.annotation.Nullable;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.syntax.Location;

/**
 * Implementation for the cc_toolchain rule.
 */
public class CcToolchain implements RuleConfiguredTargetFactory {

  /** Default attribute name where rules store the reference to cc_toolchain */
  public static final String CC_TOOLCHAIN_DEFAULT_ATTRIBUTE_NAME = ":cc_toolchain";

  public static final String CC_TOOLCHAIN_DEFAULT_ATTRIBUTE_NAME_FOR_STARLARK = "$cc_toolchain";

  /** Default attribute name for the c++ toolchain type */
  public static final String CC_TOOLCHAIN_TYPE_ATTRIBUTE_NAME = "$cc_toolchain_type";

  public static final String ALLOWED_LAYERING_CHECK_FEATURES_ALLOWLIST =
      "disabling_parse_headers_and_layering_check_allowed";
  public static final String ALLOWED_LAYERING_CHECK_FEATURES_TARGET =
      "@bazel_tools//tools/build_defs/cc/whitelists/parse_headers_and_layering_check:"
          + ALLOWED_LAYERING_CHECK_FEATURES_ALLOWLIST;
  public static final Label ALLOWED_LAYERING_CHECK_FEATURES_LABEL =
      Label.parseCanonicalUnchecked(ALLOWED_LAYERING_CHECK_FEATURES_TARGET);

  public static final String LOOSE_HEADER_CHECK_ALLOWLIST =
      "loose_header_check_allowed_in_toolchain";
  public static final String LOOSE_HEADER_CHECK_TARGET =
      "@bazel_tools//tools/build_defs/cc/whitelists/starlark_hdrs_check:" + LOOSE_HEADER_CHECK_ALLOWLIST;
  public static final Label LOOSE_HEADER_CHECK_LABEL =
      Label.parseCanonicalUnchecked(LOOSE_HEADER_CHECK_TARGET);

  @Override
  @Nullable
  public ConfiguredTarget create(RuleContext ruleContext)
      throws InterruptedException, RuleErrorException, ActionConflictException {
    validateToolchain(ruleContext);
    XcodeConfigInfo xcodeConfig = null;
    if (ruleContext.isAttrDefined(XcodeConfigRule.XCODE_CONFIG_ATTR_NAME, LABEL)) {
      xcodeConfig = XcodeConfig.getXcodeConfigInfo(ruleContext);
    }

    StarlarkFunction buildVarsFunc =
        isAppleToolchain()
            ? (StarlarkFunction)
                ruleContext.getStarlarkDefinedBuiltin("apple_cc_toolchain_build_variables")
            : (StarlarkFunction)
                ruleContext.getStarlarkDefinedBuiltin("cc_toolchain_build_variables");

    // We are storing xcodeConfig in Starlark function closure.
    buildVarsFunc =
        (StarlarkFunction)
            ruleContext.callStarlarkOrThrowRuleError(
                buildVarsFunc,
                ImmutableList.of(
                    /* xcode_config */ xcodeConfig != null ? xcodeConfig : Starlark.NONE),
                ImmutableMap.of());

    CcToolchainAttributesProvider attributes =
        new CcToolchainAttributesProvider(ruleContext, isAppleToolchain(), buildVarsFunc);

    RuleConfiguredTargetBuilder ruleConfiguredTargetBuilder =
        new RuleConfiguredTargetBuilder(ruleContext)
            .addNativeDeclaredProvider(attributes)
            .addProvider(RunfilesProvider.simple(Runfiles.EMPTY));

    if (attributes.getLicensesProvider() != null) {
      ruleConfiguredTargetBuilder.addNativeDeclaredProvider(attributes.getLicensesProvider());
    }

    if (!CppHelper.useToolchainResolution(ruleContext)) {
      // This is not a platforms-backed build, let's provide CcToolchainAttributesProvider
      // and have cc_toolchain_suite select one of its toolchains and create CcToolchainProvider
      // from its attributes. We also need to provide a do-nothing ToolchainInfo.
      return ruleConfiguredTargetBuilder
          .addNativeDeclaredProvider(new ToolchainInfo(ImmutableMap.of("cc", "dummy cc toolchain")))
          .build();
    }

    StarlarkFunction getCcToolchainProvider =
        (StarlarkFunction) ruleContext.getStarlarkDefinedBuiltin("get_cc_toolchain_provider");
    ruleContext.initStarlarkRuleContext();
    Object starlarkCcToolchainProvider =
        ruleContext.callStarlarkOrThrowRuleError(
            getCcToolchainProvider,
            ImmutableList.of(
                /* ctx */ ruleContext.getStarlarkRuleContext(),
                /* attributes */ attributes,
                /* has_apple_fragment */ isAppleToolchain()),
            ImmutableMap.of());

    // This is a platforms-backed build, we will not analyze cc_toolchain_suite at all, and we are
    // sure current cc_toolchain is the one selected. We can create CcToolchainProvider here.
    CcToolchainProvider ccToolchainProvider =
        starlarkCcToolchainProvider != Starlark.NONE
            ? (CcToolchainProvider) starlarkCcToolchainProvider
            : null;

    if (ccToolchainProvider == null) {
      // Skyframe restart
      return null;
    }

    TemplateVariableInfo templateVariableInfo =
        createMakeVariableProvider(
            ccToolchainProvider,
            ruleContext.getRule().getLocation());

    ToolchainInfo toolchain =
        new ToolchainInfo(
            ImmutableMap.<String, Object>builder()
                .put("cc", ccToolchainProvider)
                // Add a clear signal that this is a CcToolchainProvider, since just "cc" is
                // generic enough to possibly be re-used.
                .put("cc_provider_in_toolchain", true)
                .build());
    ruleConfiguredTargetBuilder
        .addNativeDeclaredProvider(ccToolchainProvider)
        .addNativeDeclaredProvider(toolchain)
        .addNativeDeclaredProvider(templateVariableInfo)
        .setFilesToBuild(ccToolchainProvider.getAllFilesIncludingLibc());
    return ruleConfiguredTargetBuilder.build();
  }

  public static TemplateVariableInfo createMakeVariableProvider(
      CcToolchainProvider toolchainProvider, Location location) {

    HashMap<String, String> makeVariables =
        new HashMap<>(toolchainProvider.getAdditionalMakeVariables());

    // Add make variables from the toolchainProvider, also.
    ImmutableMap.Builder<String, String> ccProviderMakeVariables = new ImmutableMap.Builder<>();
    toolchainProvider.addGlobalMakeVariables(ccProviderMakeVariables);
    makeVariables.putAll(ccProviderMakeVariables.buildOrThrow());

    return new TemplateVariableInfo(ImmutableMap.copyOf(makeVariables), location);
  }

  /**
   * This method marks that the toolchain at hand is actually apple_cc_toolchain. Good job me for
   * object design and encapsulation.
   */
  protected boolean isAppleToolchain() {
    // To be overridden in subclass.
    return false;
  }

  /** Will be called during analysis to ensure target attributes are set correctly. */
  protected void validateToolchain(RuleContext ruleContext) throws RuleErrorException {
    // To be overridden in subclass.
  }
}
