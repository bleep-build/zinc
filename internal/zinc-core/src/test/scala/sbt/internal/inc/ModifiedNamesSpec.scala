/*
 * Zinc - The incremental compiler for Scala.
 * Copyright Scala Center, Lightbend, and Mark Harrah
 *
 * Licensed under Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package sbt.internal.inc

import xsbti.UseScope
import xsbti.api.NameHash

class ModifiedNamesSpec extends UnitSpec:
  behavior of "ModifiedNames.compareTwoNameHashes"

  private def hash(name: String, scope: UseScope, hash: Int) = NameHash.of(name, scope, hash)

  it should "report nothing for equal name hashes" in {
    val hashes = Array(hash("a", UseScope.Default, 1), hash("b", UseScope.Implicit, 2))
    ModifiedNames.compareTwoNameHashes(hashes, hashes.reverse).names shouldBe empty
  }

  it should "report names whose hash changed, was added or was removed, with their scopes" in {
    val before = Array(
      hash("same", UseScope.Default, 1),
      hash("changed", UseScope.Default, 2),
      hash("changed", UseScope.Implicit, 3),
      hash("removed", UseScope.PatMatTarget, 4),
    )
    val after = Array(
      hash("same", UseScope.Default, 1),
      hash("changed", UseScope.Default, 20),
      hash("changed", UseScope.Implicit, 3),
      hash("added", UseScope.Default, 5),
    )
    ModifiedNames.compareTwoNameHashes(before, after).names shouldBe Set(
      UsedName("changed", Seq(UseScope.Default)),
      UsedName("removed", Seq(UseScope.PatMatTarget)),
      UsedName("added", Seq(UseScope.Default)),
    )
  }

  it should "report every name, with all its scopes, against an empty side" in {
    val hashes = Array(
      hash("a", UseScope.Default, 1),
      hash("a", UseScope.Implicit, 2),
      hash("b", UseScope.Default, 3),
    )
    val expected = Set(
      UsedName("a", Seq(UseScope.Default, UseScope.Implicit)),
      UsedName("b", Seq(UseScope.Default)),
    )
    ModifiedNames.compareTwoNameHashes(Array.empty, hashes).names shouldBe expected
    ModifiedNames.compareTwoNameHashes(hashes, Array.empty).names shouldBe expected
  }
end ModifiedNamesSpec
