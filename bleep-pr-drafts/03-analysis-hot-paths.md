# Cheaper analysis after a Java compile

Branch: `bleep/analysis-hot-paths`, on top of `bleep/classfile-java-api`. Seven independent commits. Each can be
reviewed, and dropped, on its own.

## Problem

With Java APIs read from classfiles (previous PR), the zinc work after a Java compile is about a third of the Java
compile itself. Profiling a full compile of a 39,837-class generated Java project (3,803 files, 2.57M lines, ECJ
3.44) shows no single bottleneck. Instead, several places do avoidable work per class or per dependency edge:

- `ModifiedNames.compareTwoNameHashes` builds three sets per class, then an immutable `groupBy`. On a clean build every
  class is diffed against an empty API, so this runs over every name of every class.
- `detectAPIChanges` runs after a full compilation even though nothing uses its result except the profiler.
- `AnalysisCallback` adds dependencies one edge at a time (1.2M edges here). Each edge rebuilds the persistent forward
  and reverse maps of a `Relation`.
- `NameHashing` groups members with an immutable `groupBy` and makes a `HashAPI` per name. Each `HashAPI` allocates two
  hash maps that Java members never use.
- `Discovery.pathName`, which runs for every annotation of every public member, builds each name through four
  intermediate collections.
- `ClassfileToAPI` converts a supertype's members once per source file instead of once per compile.

## Change

| commit | change |
|---|---|
| Diff name hashes without building three sets per class | one pass per side against the other's (lazy) set; scopes collected into an `EnumSet` per name |
| Add the dependencies of a compile cycle to the relations at once | new `Analysis.addDependencies`/`Relations.addDependencies`; `++` of a collection builds each context's relation with mutable builders; `joinMaps` reuses a relation the other side lacks |
| Name an annotation's path without intermediate collections | one `StringBuilder` |
| Allocate HashAPI's visited maps only when they are needed | `@threadUnsafe lazy val` |
| Do not diff APIs after a full compilation | empty `APIChanges` when `isFullCompilation` |
| Group definitions by name with a mutable map in NameHashing | `mutable.HashMap` of `ArrayBuffer`s |
| Convert each supertype's members once per Java compile | `ClassfileToAPI.Supertypes`, one per `AnalyzingJavaCompiler.compile` |

Behaviour changes:

- **Profiler output.** After "Do not diff APIs after a full compilation", a registered `RunProfiler` no longer receives
  the API changes of a full compilation's cycle (`changesAfterRecompilation`), and `-Dsbt.inc.apidebug` no longer logs
  them. They were every compiled class, and nothing else read them. This is the change to discuss.
- **Name-hash order.** The order of name hashes within a class's array can change, because it follows a `mutable.HashMap`
  instead of an immutable one. Consumers treat the array as a set, and the consistent format sorts it when it writes reproducibly.
- **New API.** `Analysis` gains an abstract `addDependencies`. `ClassfileToAPI.process` takes a `Supertypes` and a
  `Logger` explicitly.

## Effect

Same project and setup as the previous PR. bleep's compile server ran this zinc, warmed up with one untimed compile, and
a full compile of the project was profiled with JFR (`settings=profile`).

The machine was shared, with load averages of 9–23 during the runs. For the same binary, wall time ranged from 55 s to
123 s, and the ECJ share of CPU samples moved with it. CPU samples are therefore not comparable across runs. Zinc's
change in CPU samples between equally loaded runs (774 before, 786 after) is within this noise. **Allocation does not
depend on load, and it is the number this PR moves reliably.**

Zinc allocation per full compile (JFR allocation samples, excluding ECJ and bleep frames), two or three runs each:

| | before | after commit 1 | after commits 1–6 | after commits 1–7 |
|---|---|---|---|---|
| zinc allocation | 19.2 / 19.2 / 19.4 GB | 17.0 / 16.6 GB | 11.5 / 11.5 GB | **10.5 / 10.5 GB (−45%)** |

By the code each commit touches:

| commit | allocation before → after | share of zinc CPU samples before → after |
|---|---|---|
| name-hash diff | `compareTwoNameHashes` 2.9–3.0 → 0.6–0.8 GB | 11–14% → 5–9% |
| dependencies | dependency relations 2.3 → 0.36 GB; under `addSource` 2.8–3.0 → 0.4–0.5 GB | 9–11% → 4–9% (incl. the new builder) |
| `pathName` | below the noise of allocation sampling | 4–5% → 1–2% |
| lazy visited maps | `HashAPI.<init>` 0.5–1.1 GB → 0 | 0.5–2.6% → 0 |
| no diff after a full compile | `detectAPIChanges` 3.0–3.3 GB (0.9 GB after commit 1) → 0 | 13–17% → 0 |
| `NameHashing` grouping | `groupBy` 2.6–3.3 GB → 0.02 GB | 7–10% → 0.1–0.2% |
| supertypes per compile | `ownMembers` 0.8–1.1 → 0.30–0.35 GB, `inheritedDefinitions` 0.9–1.3 → 0.4–0.6 GB | 2.5–6% → 1.4–1.5% |

An in-process A/B benchmark (a throwaway `main`, not committed: same JVM, six interleaved rounds, synthetic data shaped
like the project) gave:

- adding 1.2M dependency edges took 1.2–2.0 s one edge at a time and 0.7–1.1 s all at once.
- `compareTwoNameHashes` over 40k classes × 25 names took 230–370 ms → 80–110 ms against an empty side, and
  230–450 ms → 175–230 ms with one changed hash per class.

After commit 5, commit 1 no longer affects a clean build. It still helps incremental cycles that recompile many classes.

## Tests

On every commit, `zincClassfile/testFull`, `zincApiInfo/testFull`, `zincCore/testFull`, `zincPersist/testFull` and
`zinc/testFull` pass:

| suite | tests |
|---|---|
| zinc-classfile | 37 |
| zinc-apiinfo | 58 (54 + 4 new in `DiscoverySpecification`) |
| zinc-core | 46 (43 + 3 new in `ModifiedNamesSpec`) |
| zinc-persist | 27 + 32 |
| zinc | 35 |
