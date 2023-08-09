// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.skyframe.NodeEntrySubjectFactory.assertThatNodeEntry;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Reportable;
import com.google.devtools.build.skyframe.NodeEntry.DependencyState;
import com.google.devtools.build.skyframe.NodeEntry.DirtyState;
import com.google.devtools.build.skyframe.NodeEntry.DirtyType;
import com.google.devtools.build.skyframe.SkyFunctionException.ReifiedSkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.errorprone.annotations.ForOverride;
import com.google.testing.junit.testparameterinjector.TestParameter;
import java.util.Set;
import javax.annotation.Nullable;
import org.junit.Test;

/**
 * Tests for {@link InMemoryNodeEntry} implementations.
 *
 * <p>Contains test cases that are relevant to both {@link IncrementalInMemoryNodeEntry} and {@link
 * NonIncrementalInMemoryNodeEntry}. Test cases that are only partially relevant to one or the other
 * may branch on {@link #keepEdges} and return early.
 */
abstract class InMemoryNodeEntryTest {

  private static final SkyKey REGULAR_KEY = GraphTester.toSkyKey("regular");
  private static final SkyKey PARTIAL_REEVALUATION_KEY =
      new SkyKey() {
        @Override
        public SkyFunctionName functionName() {
          return SkyFunctionName.FOR_TESTING;
        }

        @Override
        public boolean supportsPartialReevaluation() {
          return true;
        }
      };

  static final IntVersion ZERO_VERSION = IntVersion.of(0L);
  static final IntVersion ONE_VERSION = IntVersion.of(1L);

  private static final NestedSet<Reportable> NO_EVENTS =
      NestedSetBuilder.emptySet(Order.STABLE_ORDER);

  @TestParameter boolean isPartialReevaluation;

  static SkyKey key(String name) {
    return GraphTester.skyKey(name);
  }

  final InMemoryNodeEntry createEntry() {
    SkyKey key = isPartialReevaluation ? PARTIAL_REEVALUATION_KEY : REGULAR_KEY;
    return keepEdges()
        ? new IncrementalInMemoryNodeEntry(key)
        : new NonIncrementalInMemoryNodeEntry(key);
  }

  @ForOverride
  abstract boolean keepEdges();

  @Test
  public void entryAtStartOfEvaluation() {
    InMemoryNodeEntry entry = createEntry();
    entry.addReverseDepAndCheckIfDone(null); // Start evaluation.
    assertThat(entry.isDone()).isFalse();
    assertThat(entry.isReadyToEvaluate()).isTrue();
    assertThat(entry.hasUnsignaledDeps()).isFalse();
    assertThat(entry.isDirty()).isTrue();
    assertThat(entry.isChanged()).isTrue();
    assertThat(entry.getTemporaryDirectDeps()).isEmpty();
    assertThat(entry.getTemporaryDirectDeps() instanceof GroupedDeps.WithHashSet)
        .isEqualTo(isPartialReevaluation);
  }

  @Test
  public void signalEntry() throws InterruptedException {
    NodeEntry entry = createEntry();
    entry.addReverseDepAndCheckIfDone(null); // Start evaluation.
    entry.markRebuilding();
    SkyKey dep1 = key("dep1");
    entry.addSingletonTemporaryDirectDep(dep1);
    assertThat(entry.isReadyToEvaluate()).isEqualTo(isPartialReevaluation);
    assertThat(entry.hasUnsignaledDeps()).isTrue();
    assertThat(entry.signalDep(ZERO_VERSION, dep1)).isTrue();
    assertThat(entry.isReadyToEvaluate()).isTrue();
    assertThat(entry.hasUnsignaledDeps()).isFalse();
    assertThatNodeEntry(entry).hasTemporaryDirectDepsThat().containsExactly(dep1);
    SkyKey dep2 = key("dep2");
    SkyKey dep3 = key("dep3");
    entry.addSingletonTemporaryDirectDep(dep2);
    entry.addSingletonTemporaryDirectDep(dep3);
    assertThat(entry.isReadyToEvaluate()).isEqualTo(isPartialReevaluation);
    assertThat(entry.hasUnsignaledDeps()).isTrue();
    assertThat(entry.signalDep(ZERO_VERSION, dep2)).isFalse();
    assertThat(entry.isReadyToEvaluate()).isEqualTo(isPartialReevaluation);
    assertThat(entry.hasUnsignaledDeps()).isTrue();
    assertThat(entry.signalDep(ZERO_VERSION, dep3)).isTrue();
    assertThat(entry.isReadyToEvaluate()).isTrue();
    assertThat(entry.hasUnsignaledDeps()).isFalse();
    assertThat(setValue(entry, new SkyValue() {}, /* errorInfo= */ null, /* graphVersion= */ 0L))
        .isEmpty();
    assertThat(entry.isDone()).isTrue();
    assertThat(entry.getVersion()).isEqualTo(ZERO_VERSION);

    if (!keepEdges()) {
      return;
    }

    assertThat(entry.getDirectDeps()).containsExactly(dep1, dep2, dep3);
  }

  @Test
  public void signalExternalDep() throws InterruptedException {
    NodeEntry entry = createEntry();
    entry.addReverseDepAndCheckIfDone(null); // Start evaluation.
    entry.markRebuilding();
    entry.addExternalDep();
    assertThat(entry.isReadyToEvaluate()).isEqualTo(isPartialReevaluation);
    assertThat(entry.hasUnsignaledDeps()).isTrue();
    assertThat(entry.signalDep(ZERO_VERSION, null)).isTrue();
    assertThat(entry.isReadyToEvaluate()).isTrue();
    assertThat(entry.hasUnsignaledDeps()).isFalse();
    entry.addExternalDep();
    assertThat(entry.isReadyToEvaluate()).isEqualTo(isPartialReevaluation);
    assertThat(entry.hasUnsignaledDeps()).isTrue();
    assertThat(entry.signalDep(ZERO_VERSION, null)).isTrue();
    assertThat(entry.isReadyToEvaluate()).isTrue();
    assertThat(entry.hasUnsignaledDeps()).isFalse();
    assertThatNodeEntry(entry).hasTemporaryDirectDepsThat().containsExactly();
  }

  @Test
  public void reverseDeps() throws InterruptedException {
    NodeEntry entry = createEntry();
    SkyKey mother = key("mother");
    SkyKey father = key("father");
    assertThat(entry.addReverseDepAndCheckIfDone(mother))
        .isEqualTo(DependencyState.NEEDS_SCHEDULING);
    assertThat(entry.addReverseDepAndCheckIfDone(null))
        .isEqualTo(DependencyState.ALREADY_EVALUATING);
    assertThat(entry.addReverseDepAndCheckIfDone(father))
        .isEqualTo(DependencyState.ALREADY_EVALUATING);
    entry.markRebuilding();
    assertThat(setValue(entry, new SkyValue() {}, /* errorInfo= */ null, /* graphVersion= */ 0L))
        .containsExactly(mother, father);

    if (!keepEdges()) {
      return;
    }

    assertThat(entry.getReverseDepsForDoneEntry()).containsExactly(mother, father);
    assertThat(entry.isDone()).isTrue();
    entry.removeReverseDep(mother);
    assertThat(entry.getReverseDepsForDoneEntry()).doesNotContain(mother);
  }

  @Test
  public void errorValue() throws InterruptedException {
    NodeEntry entry = createEntry();
    entry.addReverseDepAndCheckIfDone(null); // Start evaluation.
    entry.markRebuilding();
    ReifiedSkyFunctionException exception =
        new ReifiedSkyFunctionException(
            new GenericFunctionException(new SomeErrorException("oops"), Transience.PERSISTENT));
    ErrorInfo errorInfo = ErrorInfo.fromException(exception, false);
    assertThat(setValue(entry, /* value= */ null, errorInfo, /* graphVersion= */ 0L)).isEmpty();
    assertThat(entry.isDone()).isTrue();
    assertThat(entry.getValue()).isNull();
    assertThat(entry.getErrorInfo()).isEqualTo(errorInfo);
  }

  @Test
  public void errorAndValue() throws InterruptedException {
    NodeEntry entry = createEntry();
    entry.addReverseDepAndCheckIfDone(null); // Start evaluation.
    entry.markRebuilding();
    ReifiedSkyFunctionException exception =
        new ReifiedSkyFunctionException(
            new GenericFunctionException(new SomeErrorException("oops"), Transience.PERSISTENT));
    ErrorInfo errorInfo = ErrorInfo.fromException(exception, false);
    setValue(entry, new SkyValue() {}, errorInfo, /* graphVersion= */ 0L);
    assertThat(entry.isDone()).isTrue();
    assertThat(entry.getErrorInfo()).isEqualTo(errorInfo);
  }

  @Test
  public void crashOnNullErrorAndValue() throws InterruptedException {
    NodeEntry entry = createEntry();
    entry.addReverseDepAndCheckIfDone(null); // Start evaluation.
    entry.markRebuilding();
    assertThrows(
        IllegalStateException.class,
        () -> setValue(entry, /* value= */ null, /* errorInfo= */ null, /* graphVersion= */ 0L));
  }

  @Test
  public void crashOnTooManySignals() {
    InMemoryNodeEntry entry = createEntry();
    entry.addReverseDepAndCheckIfDone(null); // Start evaluation.
    entry.markRebuilding();
    assertThrows(IllegalStateException.class, () -> entry.signalDep(ZERO_VERSION, null));
  }

  @Test
  public void crashOnSetValueWhenDone() throws InterruptedException {
    NodeEntry entry = createEntry();
    entry.addReverseDepAndCheckIfDone(null); // Start evaluation.
    entry.markRebuilding();
    setValue(entry, new SkyValue() {}, /* errorInfo= */ null, /* graphVersion= */ 0L);
    assertThat(entry.isDone()).isTrue();
    assertThrows(
        IllegalStateException.class,
        () -> setValue(entry, new SkyValue() {}, /* errorInfo= */ null, /* graphVersion= */ 1L));
  }

  @Test
  public void forceRebuildLifecycle() throws InterruptedException {
    NodeEntry entry = createEntry();
    entry.addReverseDepAndCheckIfDone(null); // Start evaluation.
    entry.markRebuilding();
    SkyKey dep = key("dep");
    entry.addSingletonTemporaryDirectDep(dep);
    entry.signalDep(ZERO_VERSION, dep);
    setValue(entry, new SkyValue() {}, /* errorInfo= */ null, /* graphVersion= */ 0L);
    assertThat(entry.isDirty()).isFalse();
    assertThat(entry.isDone()).isTrue();

    entry.markDirty(DirtyType.FORCE_REBUILD);
    assertThat(entry.isDirty()).isTrue();
    assertThat(entry.isChanged()).isTrue();
    assertThat(entry.isDone()).isFalse();
    assertThat(entry.getTemporaryDirectDeps() instanceof GroupedDeps.WithHashSet)
        .isEqualTo(isPartialReevaluation);

    assertThatNodeEntry(entry)
        .addReverseDepAndCheckIfDone(null)
        .isEqualTo(DependencyState.NEEDS_SCHEDULING);
    assertThat(entry.isReadyToEvaluate()).isTrue();
    assertThat(entry.hasUnsignaledDeps()).isFalse();

    SkyKey parent = key("parent");
    entry.addReverseDepAndCheckIfDone(parent);
    assertThat(entry.getDirtyState()).isEqualTo(DirtyState.NEEDS_FORCED_REBUILDING);
    assertThat(entry.isReadyToEvaluate()).isTrue();
    assertThat(entry.hasUnsignaledDeps()).isFalse();
    assertThat(entry.getTemporaryDirectDeps()).isEmpty();

    // A force-rebuilt node tolerates evaluating to different values within the same version.
    entry.forceRebuild();
    assertThat(setValue(entry, new SkyValue() {}, /* errorInfo= */ null, /* graphVersion= */ 0L))
        .containsExactly(parent);

    assertThat(entry.getVersion()).isEqualTo(ZERO_VERSION);
  }

  @Test
  public void allowTwiceMarkedForceRebuild() throws InterruptedException {
    NodeEntry entry = createEntry();
    entry.addReverseDepAndCheckIfDone(null); // Start evaluation.
    entry.markRebuilding();
    setValue(entry, new SkyValue() {}, /* errorInfo= */ null, /* graphVersion= */ 0L);
    assertThat(entry.isDirty()).isFalse();
    assertThat(entry.isDone()).isTrue();
    entry.markDirty(DirtyType.FORCE_REBUILD);
    entry.markDirty(DirtyType.FORCE_REBUILD);
    assertThat(entry.isDirty()).isTrue();
    assertThat(entry.isChanged()).isTrue();
    assertThat(entry.isDone()).isFalse();
  }

  @Test
  public void crashOnAddReverseDepTwice() throws InterruptedException {
    NodeEntry entry = createEntry();
    SkyKey parent = key("parent");
    assertThat(entry.addReverseDepAndCheckIfDone(parent))
        .isEqualTo(DependencyState.NEEDS_SCHEDULING);
    entry.addReverseDepAndCheckIfDone(parent);
    entry.markRebuilding();
    IllegalStateException e =
        assertThrows(
            "Cannot add same dep twice",
            IllegalStateException.class,
            () ->
                setValue(entry, new SkyValue() {}, /* errorInfo= */ null, /* graphVersion= */ 0L));
    assertThat(e).hasMessageThat().containsMatch("[Dd]uplicate( new)? reverse deps");
  }

  static final class IntegerValue implements SkyValue {
    private final int value;

    IntegerValue(int value) {
      this.value = value;
    }

    @Override
    public boolean equals(Object that) {
      return (that instanceof IntegerValue) && (((IntegerValue) that).value == value);
    }

    @Override
    public int hashCode() {
      return value;
    }
  }

  @Test
  public void addTemporaryDirectDepsInGroups() {
    InMemoryNodeEntry entry = createEntry();
    entry.addReverseDepAndCheckIfDone(null);
    entry.addTemporaryDirectDepsInGroups(
        ImmutableSet.of(
            key("1A"), key("2A"), key("2B"), key("3A"), key("3B"), key("3C"), key("4A"), key("4B"),
            key("4C"), key("4D")),
        ImmutableList.of(1, 2, 3, 4));
    assertThat(entry.getTemporaryDirectDeps())
        .containsExactly(
            ImmutableList.of(key("1A")),
            ImmutableList.of(key("2A"), key("2B")),
            ImmutableList.of(key("3A"), key("3B"), key("3C")),
            ImmutableList.of(key("4A"), key("4B"), key("4C"), key("4D")))
        .inOrder();
  }

  @Test
  public void addTemporaryDirectDepsInGroups_toleratesEmpty() {
    InMemoryNodeEntry entry = createEntry();
    entry.addReverseDepAndCheckIfDone(null);
    entry.addTemporaryDirectDepsInGroups(ImmutableSet.of(), ImmutableList.of());
    assertThat(entry.getTemporaryDirectDeps()).isEmpty();
  }

  @Test
  public void addTemporaryDirectDepsInGroups_toleratesGroupSizeOfZero() {
    InMemoryNodeEntry entry = createEntry();
    entry.addReverseDepAndCheckIfDone(null);
    entry.addTemporaryDirectDepsInGroups(ImmutableSet.of(key("dep")), ImmutableList.of(0, 1, 0));
    assertThat(entry.getTemporaryDirectDeps()).containsExactly(ImmutableList.of(key("dep")));
  }

  @Test
  public void addTemporaryDirectDepsInGroups_notEnoughGroups_throws() {
    InMemoryNodeEntry entry = createEntry();
    entry.addReverseDepAndCheckIfDone(null);
    assertThrows(
        RuntimeException.class,
        () ->
            entry.addTemporaryDirectDepsInGroups(ImmutableSet.of(key("dep")), ImmutableList.of()));
  }

  @Test
  public void addTemporaryDirectDepsInGroups_tooManyGroups_throws() {
    InMemoryNodeEntry entry = createEntry();
    assertThrows(
        RuntimeException.class,
        () -> entry.addTemporaryDirectDepsInGroups(ImmutableSet.of(), ImmutableList.of(1)));
  }

  @Test
  public void addTemporaryDirectDepsInGroups_depsLeftOver_throws() {
    InMemoryNodeEntry entry = createEntry();
    entry.addReverseDepAndCheckIfDone(null);
    assertThrows(
        RuntimeException.class,
        () ->
            entry.addTemporaryDirectDepsInGroups(
                ImmutableSet.of(key("1"), key("2"), key("3")), ImmutableList.of(1, 1)));
  }

  @Test
  public void addTemporaryDirectDepsInGroups_depsExhausted_throws() {
    InMemoryNodeEntry entry = createEntry();
    entry.addReverseDepAndCheckIfDone(null);
    assertThrows(
        RuntimeException.class,
        () ->
            entry.addTemporaryDirectDepsInGroups(
                ImmutableSet.of(key("1"), key("2"), key("3")), ImmutableList.of(1, 1, 2)));
  }

  static Set<SkyKey> setValue(
      NodeEntry entry, SkyValue value, @Nullable ErrorInfo errorInfo, long graphVersion)
      throws InterruptedException {
    return entry.setValue(
        ValueWithMetadata.normal(value, errorInfo, NO_EVENTS), IntVersion.of(graphVersion), null);
  }
}
