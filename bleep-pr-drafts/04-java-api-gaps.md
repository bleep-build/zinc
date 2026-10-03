# Invalidate Java dependents on API changes zinc did not see

Branch: `bleep/java-api-gaps`, on top of `bleep/analysis-hot-paths`. Six commits, one fix each, each with a unit test
and a scripted test. Fix 3 (`HashAPI`) is shared code that also fixes a Scala gap; the others are in the Java API
extractors. Commits 1, 3 and 4 are relevant to upstream's `ClassToAPI` as well.

## Problem

A Java class can change in ways that break, or silently change, the classes that depend on it, while zinc does not
recompile those dependents. The build stays green, and a clean build fails (or, in one case, the program computes
wrong values).

For a Java dependent, zinc's only gate is whether the changed class's API hash differs: name-hash filtering applies to
Scala dependents only, and Java dependents record an edge to every class they reference. An audit of 38 change
scenarios (cross-module scripted tests) found 16 misses. In every one, the dependency edge was there and the API hash
did not move.

Example: `sealed interface S permits X, Y`, and a dependent with an exhaustive `switch (s) { case X x -> ...; case Y y
-> ...; }`. Adding `Z` to `permits` changes only the `PermittedSubclasses` attribute of `S.class`. Nothing reads it, so
the API, its hash and the name hashes are unchanged, and the dependent is not recompiled, although it no longer compiles.

## Root causes and fixes

| commit | cause | missed before |
|---|---|---|
| Record a sealed Java class's permitted subclasses in its API | `PermittedSubclasses` not modelled (both extractors) | a type added to `permits`, sealed → open, open → sealed |
| Tell a Java varargs method from its array twin | `ACC_VARARGS` ignored by the classfile extractor | varargs → array parameter |
| Hash a class's own modifiers, access, annotations and kind into its API hash | `HashAPI.hashAPI` hashed a class's members but not its header; name hashes have the header, but are only compared once API hashes differ | `final`/`abstract` added to a class, a class made package-private (Scala: `final class`, `private[p] class`), non-sealed → final, a class's type parameters, bounds or generic supertypes |
| Record a Java record's component order in its API | component order not modelled (both extractors) | swapping two same-typed record components: **no compile error, wrong values at run time** |
| Read a nested Java class's declared access from its InnerClasses entry | a nested class's real access is in `InnerClasses`, not its `access_flags` | nested class public → protected |
| Fold Java annotation element values and parameter annotations into the API | only annotation type names were recorded | an annotation's element value changed, a parameter annotation added |

Already invalidated correctly before, and still: enum constants, constant values, record components added or removed,
method visibility/`final`/`static`/`throws`/generic signatures, default methods, annotation type changes, and changes of
kind (class ↔ interface/enum/record, interface ↔ annotation, static nested ↔ inner, top-level ↔ nested). Kind changes
were caught only because they always change some member; with the `HashAPI` fix the kind is hashed directly.

## Behaviour changes

- **Every API hash changes value once** (the `HashAPI` fix), so the first incremental compile after upgrading sees each
  recompiled class as changed, as after any change to `HashAPI`.
- A change to a class's annotations (e.g. `@Deprecated` added) now changes its API hash and recompiles its Java
  dependents.

## Not covered

- TYPE_USE annotations (`RuntimeVisibleTypeAnnotations`, e.g. JSpecify `@Nullable` checked by NullAway in a dependent):
  not modelled by either extractor.
- `module-info`: not modelled, and no dependent has an edge to it.

## Tests

- New scripted tests: `java-sealed-permits-add`, `java-sealed-unsealed`, `java-sealed-class-sealed`,
  `java-varargs-removed`, `java-class-final-added`, `java-class-type-param-bound`, `class-final-added-cross-module`
  (Scala), `java-record-component-reorder`, `java-nested-class-protected`, `java-annotation-element-value`. Each fails
  without its fix.
- Unit tests in `ClassfileToAPISpecification` and `HashAPISpecification`.
- `zincApiInfo/testFull`, `zincClassfile/testFull`, `zincCore/testFull` and `zinc/testFull` pass, and
  `scripted source-dependencies/* apiinfo/* general/*` passes with the new tests (202 passed, 0 failed): no existing
  Scala scripted test changed outcome.
