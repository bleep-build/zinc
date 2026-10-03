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

package xsbt.api

import xsbti.api.*
import sbt.internal.inc.UnitSpec

class DiscoverySpecification extends UnitSpec:

  private def path(components: PathComponent*) = Path.of(components.toArray)

  "Discovery.pathName" should "join the ids of a path that ends in `this`" in {
    val p = path(Id.of("java"), Id.of("lang"), This.of())
    Discovery.pathName(p, "Deprecated") shouldBe Some("java.lang.Deprecated")
  }

  it should "name a type directly under `this`" in {
    Discovery.pathName(path(This.of()), "Foo") shouldBe Some("Foo")
  }

  it should "not name a path that does not end in `this`" in {
    Discovery.pathName(path(Id.of("a"), Id.of("b")), "Foo") shouldBe None
  }

  it should "not name a path with anything but ids before `this`" in {
    val p = path(Id.of("a"), Super.of(path(This.of())), This.of())
    Discovery.pathName(p, "Foo") shouldBe None
  }
end DiscoverySpecification
