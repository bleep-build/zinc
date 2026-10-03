# Parse each classfile once per ClassToAPI run

Branch: `bleep/classtoapi-classfile-cache`, on `develop`.

## Problem

`ClassToAPI.structure` reads the classfile of the class it analyses, and `innerClassesFromClassfile` also reads the
classfile of every supertype of that class, to find its inherited public inner classes. Nothing caches these reads, so
a supertype shared by many classes is read from disk and parsed again for each of them.

On projects with large class hierarchies — a generated AST, say: 39,837 classes, most of them in a few deep
hierarchies — this dominates the analysis after a Java compile.

## Change

Cache each class's parsed `ClassFile` in `ClassToAPI.ClassMap`, which already holds the per-run state and is cleared
at the end of `process`. Both call sites read through it. One file, +11/−2, no signature changes.

## Effect

A full compile of that 2.57M-line project (ECJ 3.44, zinc `develop`, warm JVM):

| | before | after |
|---|---|---|
| `ClassToAPI` CPU samples | 4,226 | 1,243 (−71%) |
| `ClassToAPI` allocation | 88 GB | 30 GB |
| total allocation during the compile | 363 GB | 246 GB |

## Tests

The existing `zinc-classfile`, `zinc-apiinfo` and `zinc` tests pass (37, 54, 35). Behaviour does not change; it only
avoids re-parsing.
