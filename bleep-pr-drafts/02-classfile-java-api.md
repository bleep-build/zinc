# Extract Java APIs from classfiles instead of loading every class

Branch: `bleep/classfile-java-api`, on top of `bleep/classtoapi-classfile-cache`.

## Problem

After a Java compile, `AnalyzingJavaCompiler.readAPI` builds each compiled class's `xsbti.api.ClassLike` by loading it
reflectively: `JavaAnalyze` calls `Class.forName` on every class javac produced, into a `URLClassLoader` spanning the
whole classpath that is discarded when the compile ends. On large projects this costs more than the Java compile
itself, and it fails on classes reflection cannot load, which is what the sbt/zinc#837 fallback is for.

## Change

Build the API from the classfiles `JavaAnalyze` has already parsed:

- `readAPI` takes `Seq[(String, ClassFile)]` plus a supertype resolver, and returns the inheritance edges directly from
  `superClassName` and `interfaceNames`.
- Local and anonymous classes are told apart by `canonicalClassName`, which is `None` for exactly the classes
  `Class.getCanonicalName` returned null for.
- `loadEnclosingClass` is replaced by `enclosingSourceName`, which walks the `InnerClasses` attribute.
- The resolver is zinc's existing `resolveClassFile`: this batch first, then the classpath via `getResource`, memoized.
  It reads bytes and never defines a class.
- `ClassfileToAPI` gains inherited members, which it previously left empty. This is required:
  `HashAPI.hashStructure0` hashes both `structure.declared` and `structure.inherited`, so without them a class whose
  supertype changed keeps its old hash and its dependents are never invalidated. Supertype members are converted once
  per batch and concatenated in a fixed order, so the result stays deterministic.
- This makes the sbt/zinc#837 fallback unnecessary: nothing is loaded any more.

The tests that drove `JavaAnalyze` through the reflective path now use the classfile one, and
`ClassToAPISpecification` calls `ClassToAPI` directly, since zinc's Java analysis no longer goes through it.

This builds on the previous PR but does not depend on it: Java analysis no longer calls `ClassToAPI`, and the new path
has the same caching in its own form — `resolveClassFile` parses each supertype once per compile, and
`ClassfileToAPI.process` converts each supertype's members once per batch.

## Effect

Same project and setup, against `develop`:

| | `develop` | this change |
|---|---|---|
| zinc CPU samples (analysis after the Java compile) | ~7,580 | ~1,020 (−87%) |
| total allocation | 363 GB | 149 GB |
| compile wall time | 214 s | 55 s |

Earlier, on a 96,165-class project, metaspace after a full compile fell from a 965 MB peak to 469 MB and the transient
classloader swings disappeared.

## Tests

The `zinc-classfile`, `zinc-apiinfo` and `zinc` tests pass (37, 54, 35).
