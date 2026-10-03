# PR drafts for upstream (sbt/zinc)

This branch, `develop-fork`, is zinc's `develop` with the branches below merged in. bleep builds zinc from it
(`liberated/zinc`). Each branch is one upstream PR, stacked in this order, each on top of the previous:

1. `bleep/classtoapi-classfile-cache` — [01-classtoapi-classfile-cache.md](01-classtoapi-classfile-cache.md)
2. `bleep/classfile-java-api` — [02-classfile-java-api.md](02-classfile-java-api.md)
3. `bleep/analysis-hot-paths` — [03-analysis-hot-paths.md](03-analysis-hot-paths.md)
4. `bleep/java-api-gaps` — [04-java-api-gaps.md](04-java-api-gaps.md)

To update after upstream moves: rebase the first branch on `develop`, each next branch on the previous one, and
merge the last into a fresh `develop-fork` along with this directory.
