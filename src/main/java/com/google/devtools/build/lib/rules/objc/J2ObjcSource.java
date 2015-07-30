// Copyright 2014 Google Inc. All rights reserved.
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

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;

/**
 * An object that captures information of ObjC files generated by J2ObjC in a single target.
 */
public class J2ObjcSource {

  /**
   * Indicates the type of files from which the ObjC files included in {@link J2ObjcSource} are
   * generated.
   */
  public enum SourceType {
    /**
     * Indicates the original file type is java source file.
     */
    JAVA,

    /**
     * Indicates the original file type is proto file.
     */
    PROTO;
  }

  private final Label targetLabel;
  private final Iterable<Artifact> objcSrcs;
  private final Iterable<Artifact> objcHdrs;
  private final PathFragment objcFilePath;
  private final SourceType sourceType;

  /**
   * Constructs a J2ObjcSource containing target information for j2objc transpilation.
   *
   * @param targetLabel the @{code Label} of the associated target.
   * @param objcSrcs the {@code Iterable} containing objc source files generated by J2ObjC
   * @param objcHdrs the {@code Iterable} containing objc header files generated by J2ObjC
   * @param objcFilePath the {@code PathFragment} under which all the generated objc files are. It
   *     can be used as header search path for objc compilations.
   * @param sourceType the type of files from which the ObjC files are generated.
   */
  public J2ObjcSource(Label targetLabel, Iterable<Artifact> objcSrcs,
      Iterable<Artifact> objcHdrs, PathFragment objcFilePath, SourceType sourceType) {
    this.targetLabel = targetLabel;
    this.objcSrcs = objcSrcs;
    this.objcHdrs = objcHdrs;
    this.objcFilePath = objcFilePath;
    this.sourceType = sourceType;
  }

  /**
   * Returns a corresponding {@link J2ObjcSource} with source artifacts replaced by the outputs of
   * the J2objC dead code removal script, for use after that action has processed the originals.
   *
   * <p>The script in question builds a dependency graph with entry classes specified
   * transitively on j2objc_library rules as roots. Translated files from this (original)
   * {@link J2ObjcSource} which are reachable in the graph from the roots will be copied over to the
   * source file paths in the returned pruned {@link J2ObjcSource} with full original contents.
   * Unreachable files will not be copied over and the artifacts pointed to by the returned pruned 
   * {@link J2ObjcSource} will only contain empty files.
   *
   * @param ruleContext the {@link RuleContext} of the current rule
   */
  public J2ObjcSource toPrunedSource(RuleContext ruleContext) {
    ImmutableList.Builder<Artifact> prunedSourceArtifacts = ImmutableList.builder();

    for (Artifact sourceArtifact : getObjcSrcs()) {
      PathFragment prunedSourceArtifactPath = FileSystemUtils.appendWithoutExtension(
          sourceArtifact.getRootRelativePath(), "_pruned");
      Artifact prunedArtifact = ruleContext.getUniqueDirectoryArtifact("_j2objc_pruned",
          prunedSourceArtifactPath, ruleContext.getBinOrGenfilesDirectory());
      prunedSourceArtifacts.add(prunedArtifact);
    }

    return new J2ObjcSource(
        getTargetLabel(),
        prunedSourceArtifacts.build(),
        getObjcHdrs(),
        getObjcFilePath(),
        getSourceType());
  }

  /**
   * Returns the label of the associated target.
   */
  public Label getTargetLabel() {
    return targetLabel;
  }

  /**
   * Returns the objc source files generated by J2ObjC.
   */
  public Iterable<Artifact> getObjcSrcs() {
    return objcSrcs;
  }

  /*
   * Returns the objc header files generated by J2ObjC
   */
  public Iterable<Artifact> getObjcHdrs() {
    return objcHdrs;
  }

  /**
   * Returns the {@code PathFragment} which represents a directory where the generated ObjC files
   * reside and which can also be used as header search path in ObjC compilation.
   */
  public PathFragment getObjcFilePath() {
    return objcFilePath;
  }

  /**
   * Returns the type of files from which the ObjC files inside this object are generated.
   */
  public SourceType getSourceType() {
    return sourceType;
  }

  @Override
  public final boolean equals(Object other) {
    if (!(other instanceof J2ObjcSource)) {
      return false;
    }

    J2ObjcSource that = (J2ObjcSource) other;
    return Objects.equal(this.targetLabel, that.targetLabel)
        && Iterators.elementsEqual(this.objcSrcs.iterator(), that.objcSrcs.iterator())
        && Iterators.elementsEqual(this.objcHdrs.iterator(), that.objcHdrs.iterator())
        && Objects.equal(this.objcFilePath, that.objcFilePath)
        && this.sourceType == that.sourceType;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(targetLabel, objcSrcs, objcHdrs, objcFilePath, sourceType);
  }
}

