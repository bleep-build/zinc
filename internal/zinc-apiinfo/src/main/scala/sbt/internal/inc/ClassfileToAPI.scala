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

package sbt
package internal
package inc

import java.io.{ ByteArrayInputStream, DataInputStream }
import java.lang.reflect.Modifier
import scala.collection.mutable.ArrayBuffer
import xsbti.api
import xsbti.api.SafeLazyProxy
import sbt.internal.inc.classfile.{ AttributeInfo, ClassFile, FieldOrMethodInfo }
import sbt.util.Logger

/**
 * Builds an [[xsbti.api.ClassLike]] for a Java class directly from its classfile, without loading
 * it via reflection. This is the fallback used for classes that cannot be reflectively loaded
 * during post-compile analysis (sbt/zinc#837, sbt/sbt#117) — without it those classes get no API
 * recorded, so name-hashing can miss dependents when the class's own public shape changes.
 *
 * It mirrors the structure [[ClassToAPI]] produces (a class + module `ClassLike` pair, declared
 * members split into static/instance) and reuses [[ClassToAPI]]'s access/modifier/type helpers, so
 * the result slots into the same analysis. Member types are erased (from JVM descriptors), but
 * generic signatures, checked exceptions, and declared annotations are folded in conservatively (raw
 * `Signature` string / `throws` and annotation *type* names — not annotation element values), so a
 * change to any of them still changes the hash. A sealed class's permitted subclasses are its
 * children. Enum children and type parameters are not modelled — see the "Phase 2" scope in
 * docs/design/classfile-based-java-api.md. The output need not match the reflection-derived API
 * byte-for-byte; it only needs to be deterministic and to change when the class's public shape
 * changes.
 *
 * Inherited members ARE modelled, via [[inheritedDefinitions]] and the caller-supplied [[Resolve]].
 * Without them a subclass whose supertype changed keeps its old hash — its own classfile is
 * unchanged — and its dependents are never invalidated, which is a stale build rather than a slow
 * one.
 */
object ClassfileToAPI:
  import api.DefinitionType.{ ClassDef, Module, Trait }

  private val noAnnotations = new Array[api.Annotation](0)
  private val noTypeParameters = new Array[api.TypeParameter](0)
  private val noTypes = new Array[api.Type](0)
  private val noStrings = new Array[String](0)
  private val noDefinitions = new Array[api.ClassDefinition](0)

  private def strict[T <: AnyRef](t: T): api.Lazy[T] = SafeLazyProxy.strict(t)

  // A synthetic annotation that folds public-shape signals the erased descriptor can't capture —
  // the raw generic Signature, checked-exception (throws) types, and declared annotation types —
  // into the API, so changing any of them still changes the hash. Conservative Phase-2 fold (raw
  // Signature string and exception/annotation *type* names, not annotation element values); proper
  // modelling is Phase 3.
  private val SyntheticRef = ClassToAPI.reference("xsbti.api.ClassfileApi")

  private def syntheticAnnotations(parts: (String, Iterable[String])*): Array[api.Annotation] =
    val args = parts.flatMap {
      case (label, values) =>
        if values.isEmpty then None
        else Some(api.AnnotationArgument.of(label, values.mkString(",")))
    }
    if args.isEmpty then noAnnotations
    else Array(api.Annotation.of(SyntheticRef, args.toArray))

  /** Resolved checked-exception type names from a method's `Exceptions` attribute (JVMS 4.7.5). */
  private def exceptionNames(cf: ClassFile, attrs: Seq[AttributeInfo]): Seq[String] =
    attrs.find(_.isNamed("Exceptions")) match
      case None    => Nil
      case Some(a) =>
        try
          val in = new DataInputStream(new ByteArrayInputStream(a.value))
          val count = in.readUnsignedShort()
          List.fill(count) {
            val classConstant = cf.constantPool(in.readUnsignedShort())
            cf.constantPool(classConstant.nameIndex).value.fold("")(_.toString).replace('/', '.')
          }
        catch case _: Throwable => Nil

  /**
   * The binary names in a sealed class's `PermittedSubclasses` attribute (JVMS 4.7.31), empty for a
   * class that is not sealed. javac writes the attribute whether the subclasses are listed in a
   * `permits` clause or inferred from the compilation unit.
   */
  private def permittedSubclassNames(cf: ClassFile): Seq[String] =
    cf.attributes.find(_.isNamed("PermittedSubclasses")) match
      case None    => Nil
      case Some(a) =>
        val in = new DataInputStream(new ByteArrayInputStream(a.value))
        val count = in.readUnsignedShort()
        List.fill(count) {
          val classConstant = cf.constantPool(in.readUnsignedShort())
          cf.constantPool(classConstant.nameIndex).value match
            case Some(name) => name.toString.replace('/', '.')
            case None       =>
              throw new IllegalStateException(
                s"${cf.className}: PermittedSubclasses entry $classConstant names no class"
              )
        }

  /**
   * A record's components in declaration order, as `name:descriptor`, from its `Record` attribute
   * (JVMS 4.7.30); empty for a class that is not a record.
   */
  private def recordComponents(cf: ClassFile): Seq[String] =
    cf.attributes.find(_.isRecord) match
      case None    => Nil
      case Some(a) =>
        val in = new DataInputStream(new ByteArrayInputStream(a.value))
        def utf8(i: Int): String = cf.constantPool(i).value match
          case Some(value) => value.toString
          case None        =>
            throw new IllegalStateException(s"${cf.className}: Record names no string at $i")
        val count = in.readUnsignedShort()
        List.fill(count) {
          val name = utf8(in.readUnsignedShort())
          val descriptor = utf8(in.readUnsignedShort())
          val attributes = in.readUnsignedShort()
          (0 until attributes).foreach { _ =>
            in.readUnsignedShort()
            in.readFully(new Array[Byte](in.readInt()))
          }
          s"$name:$descriptor"
        }

  /** Declared annotation type names from RuntimeVisible/Invisible annotations (JVMS 4.7.16). */
  private def annotationTypeNames(cf: ClassFile, attrs: Seq[AttributeInfo]): Seq[String] =
    val names = ArrayBuffer.empty[String]
    for a <- attrs if a.isRuntimeVisibleAnnotations || a.isRuntimeInvisibleAnnotations do
      try
        val in = new DataInputStream(new ByteArrayInputStream(a.value))
        def utf8(i: Int): String = cf.constantPool(i).value.fold("")(_.toString)
        def skipElementValue(): Unit =
          in.readUnsignedByte().toChar match
            case 'e' => in.readUnsignedShort(); in.readUnsignedShort()
            case 'c' => in.readUnsignedShort()
            case '@' => readAnnotation()
            case '[' =>
              val n = in.readUnsignedShort()
              (0 until n).foreach(_ => skipElementValue())
            case _ => in.readUnsignedShort()
        def readAnnotation(): Unit =
          names += utf8(in.readUnsignedShort()).stripPrefix("L").stripSuffix(";").replace('/', '.')
          val pairs = in.readUnsignedShort()
          (0 until pairs).foreach { _ => in.readUnsignedShort(); skipElementValue() }
        val num = in.readUnsignedShort()
        (0 until num).foreach(_ => readAnnotation())
      catch case _: Throwable => ()
    names.toSeq
  end annotationTypeNames

  /**
   * Produces the API (class + module `ClassLike` for each input) and the names of any classes that
   * declare a `main` method. Each input pairs the class's source (canonical) name — supplied by the
   * caller so it stays consistent with the product/dependency relations — with its parsed classfile.
   */
  def process(
      named: Seq[(String, ClassFile)],
      resolve: Resolve,
      supertypes: Supertypes,
      log: Logger
  ): (Seq[api.ClassLike], Seq[String]) =
    val classApis = ArrayBuffer.empty[api.ClassLike]
    val mainClasses = ArrayBuffer.empty[String]
    for (name, cf) <- named do
      classApis ++= classLikes(name, cf, resolve, supertypes.members)
      if cf.methods.exists(_.isMain) then mainClasses += name
    (classApis.toSeq, mainClasses.toSeq)

  /**
   * Looks up a supertype's classfile by binary name. Returning None is normal and safe — see
   * [[inheritedDefinitions]].
   */
  type Resolve = String => Option[ClassFile]

  /**
   * A resolver that knows only the batch being processed. Supertypes outside it — the JDK, other
   * projects — go unresolved.
   */
  def resolveWithin(named: Seq[(String, ClassFile)]): Resolve =
    val byBinaryName = named.iterator.map { case (_, cf) => cf.className -> cf }.toMap
    byBinaryName.get(_)

  /**
   * Members this class inherits, as (static, instance) — the `structure.inherited` that reflection
   * gets for free from `Class.getMethods`/`getFields`.
   *
   * This exists for hash sensitivity, not for a faithful member list. `HashAPI.hashStructure0`
   * hashes `declared` AND `inherited`, so without it a change to a supertype in another project
   * leaves the subclass's hash unmoved and the subclass's dependents are never invalidated.
   *
   * Deliberately conservative in three ways, all erring toward over-sensitivity (a wasted recompile)
   * rather than under (a stale build):
   *   - unresolvable supertypes are skipped rather than failing. The JDK is the common case, and it
   *     does not change within a build.
   *   - inherited members are not de-duplicated against the subclass's own declarations, so an
   *     override moves the hash.
   *   - private members are excluded, as are constructors; Java inherits neither.
   */
  private type Members = (Array[api.ClassDefinition], Array[api.ClassDefinition])

  /**
   * The members of supertypes, converted once and shared by every class that inherits them.
   * Without it every subclass re-walks and re-converts its entire supertype chain, which on a deep
   * hierarchy costs more than the reflection this replaces. Use one per compile, so that the
   * classes of every source share it: a name must keep resolving to the same classfile for as
   * long as an instance is used.
   */
  final class Supertypes:
    private[ClassfileToAPI] val members = new java.util.HashMap[String, Members]

  private def inheritedDefinitions(
      cf: ClassFile,
      resolve: Resolve,
      memo: java.util.HashMap[String, Members]
  ): (Array[api.ClassDefinition], Array[api.ClassDefinition]) =
    val statics = ArrayBuffer.empty[api.ClassDefinition]
    val instances = ArrayBuffer.empty[api.ClassDefinition]
    val seen = scala.collection.mutable.HashSet.empty[String]

    /**
     * This type's own members, converted once and reused by every subclass in the compile. Ordered by
     * declaration, which is fixed by the classfile — so callers can concatenate without re-sorting.
     */
    def ownMembers(parent: ClassFile): Members =
      val cached = memo.get(parent.className)
      if cached != null then cached
      else
        val st = ArrayBuffer.empty[api.ClassDefinition]
        val inst = ArrayBuffer.empty[api.ClassDefinition]
        val pkg = ClassToAPI.packageAndName(parent.className)._1
        for f <- parent.fields if !Modifier.isPrivate(f.accessFlags) do
          val (isStatic, d) = fieldDef(parent, f, pkg)
          if isStatic then st += d else inst += d
        for m <- parent.methods if !Modifier.isPrivate(m.accessFlags) do
          val n = m.name.getOrElse("")
          if !n.contains("<init>") && !n.contains("<clinit>") then
            val (isStatic, d) = methodDef(parent, m, parent.className, pkg)
            if isStatic then st += d else inst += d
        val result: Members = (st.toArray, inst.toArray)
        memo.put(parent.className, result)
        result

    // Depth-first, superclass before interfaces, `seen` de-duplicating: a fixed order derived from
    // the classfile alone. That is what makes the plain concatenation below deterministic — sorting
    // per subclass instead would re-walk every inherited member of every class, which on a 96k-class
    // project costs more than the reflection this replaces.
    def walk(binaryName: String): Unit =
      if binaryName.nonEmpty && seen.add(binaryName) then
        resolve(binaryName).foreach { parent =>
          val (st, inst) = ownMembers(parent)
          statics ++= st
          instances ++= inst
          (parent.superClassName +: parent.interfaceNames.toIndexedSeq).foreach(walk)
        }

    // A class does not inherit from itself; seeding `seen` also stops a cyclic classpath looping.
    seen += cf.className
    (cf.superClassName +: cf.interfaceNames.toIndexedSeq).foreach(walk)

    (statics.toArray, instances.toArray)
  end inheritedDefinitions

  private def classLikes(
      name: String,
      cf: ClassFile,
      resolve: Resolve,
      memo: java.util.HashMap[String, Members]
  ): Seq[api.ClassLike] =
    // Use the binary name's package (last '.' before the simple/binary name); the canonical name's
    // dots would mis-split nested classes (e.g. "pkg.Outer.Inner").
    val enclPkg = ClassToAPI.packageAndName(cf.className)._1
    // A sealed class's permitted subclasses decide which `switch`es over it are exhaustive and which
    // classes may extend it, so they are its children, as an enum's constants are in ClassToAPI.
    // HashAPI hashes children, so adding, removing or unsealing a subclass changes the hash.
    val permitted = permittedSubclassNames(cf)
    val children: Array[api.Type] = permitted.map(ClassToAPI.reference).toArray
    val mods = classModifiers(cf.accessFlags, permitted.nonEmpty)
    val acc = ClassToAPI.access(cf.accessFlags, enclPkg)
    val isInterface = Modifier.isInterface(cf.accessFlags)
    val tpe = if isInterface then Trait else ClassDef
    // Top-level unless the classfile's InnerClasses attribute lists itself as a member of another.
    val topLevel =
      !cf.innerClasses.exists(i => i.innerClassName == cf.className && i.outerClassName.nonEmpty)

    val fields = cf.fields.toIndexedSeq.map(fieldDef(cf, _, enclPkg))
    val methods = cf.methods.toIndexedSeq.collect {
      case m if !m.name.contains("<clinit>") => methodDef(cf, m, cf.className, enclPkg)
    }
    val (staticFields, instanceFields) = fields.partition(_._1)
    val (staticMethods, instanceMethods) = methods.partition(_._1)
    val instanceDeclared: Array[api.ClassDefinition] =
      (instanceFields.map(_._2) ++ instanceMethods.map(_._2)).toArray
    val staticDeclared: Array[api.ClassDefinition] =
      (staticFields.map(_._2) ++ staticMethods.map(_._2)).toArray

    val parents: Array[api.Type] = (cf.superClassName +: cf.interfaceNames.toIndexedSeq)
      .filter(_.nonEmpty)
      .map(ClassToAPI.reference)
      .toArray

    // A record pattern binds components by position, so `record R(int x, int y)` becoming
    // `record R(int y, int x)` changes what `case R(int a, int b)` binds, though the members and the
    // canonical constructor's descriptor stay the same. Hence the component order.
    val classAnnots = syntheticAnnotations(
      "signature" -> cf.attributes.find(_.isSignature).map(cf.stringValue).toList,
      "annotations" -> annotationTypeNames(cf, cf.attributes.toIndexedSeq),
      "record" -> recordComponents(cf)
    )

    val (staticInherited, instanceInherited) = inheritedDefinitions(cf, resolve, memo)

    val instanceStructure =
      api.Structure.of(strict(parents), strict(instanceDeclared), strict(instanceInherited))
    val staticStructure =
      api.Structure.of(strict(noTypes), strict(staticDeclared), strict(staticInherited))

    val cls = api.ClassLike.of(
      name,
      acc,
      mods,
      classAnnots,
      tpe,
      strict(ClassToAPI.Empty),
      strict(instanceStructure),
      noStrings,
      children,
      topLevel,
      noTypeParameters
    )
    val stat = api.ClassLike.of(
      name,
      acc,
      mods,
      classAnnots,
      Module,
      strict(ClassToAPI.Empty),
      strict(staticStructure),
      noStrings,
      noTypes,
      topLevel,
      noTypeParameters
    )
    cls :: stat :: Nil
  end classLikes

  /**
   * [[ClassToAPI.modifiers]] plus `sealed`, which no JVM access flag carries. With
   * `useOptimizedSealed`, NameHashing gives children only to classes marked sealed, so without the
   * flag a Scala dependent matching on a sealed Java type would not see its children change.
   */
  private def classModifiers(accessFlags: Int, isSealed: Boolean): api.Modifiers =
    new api.Modifiers(
      Modifier.isAbstract(accessFlags),
      false,
      Modifier.isFinal(accessFlags),
      isSealed,
      false,
      false,
      false,
      false
    )

  /** (isStatic, FieldLike) for a classfile field. */
  private def fieldDef(
      cf: ClassFile,
      f: FieldOrMethodInfo,
      enclPkg: Option[String]
  ): (Boolean, api.ClassDefinition) =
    val name = f.name.getOrElse("")
    val mods = ClassToAPI.modifiers(f.accessFlags)
    // Compilers inline static-final constants, so fold the constant value into the type (mirroring
    // ClassToAPI.singletonForConstantField) so a value change (X = 1 -> 2) changes the hash.
    val constant: Option[AnyRef] =
      if mods.isFinal then
        (try cf.constantValue(name)
        catch case _: Throwable => None)
      else None
    val tpe = constant match
      case Some(value) =>
        val tag = name + "$" + f.descriptor.getOrElse("") + "$" + value
        api.Singleton.of(ClassToAPI.pathFromStrings(cf.className.split("\\.").toIndexedSeq :+ tag))
      case None => parseFieldType(f.descriptor.getOrElse("V"))
    val acc = ClassToAPI.access(f.accessFlags, enclPkg)
    val annots = syntheticAnnotations(
      "signature" -> f.attributes.find(_.isSignature).map(cf.stringValue).toList,
      "annotations" -> annotationTypeNames(cf, f.attributes)
    )
    val fieldLike =
      if mods.isFinal then api.Val.of(name, acc, mods, annots, tpe)
      else api.Var.of(name, acc, mods, annots, tpe)
    (f.isStatic, fieldLike)
  end fieldDef

  /** (isStatic, Def) for a classfile method; `<init>` is named like [[ClassToAPI]]'s constructors. */
  private def methodDef(
      cf: ClassFile,
      m: FieldOrMethodInfo,
      binaryName: String,
      enclPkg: Option[String]
  ): (Boolean, api.ClassDefinition) =
    val (paramTypes, returnType) = parseMethodType(m.descriptor.getOrElse("()V"))
    // `m(String... a)` and `m(String[] a)` share a descriptor, but only the first accepts
    // `m("a", "b")`. Mark the varargs parameter as ClassToAPI.defLike does.
    val varArgPosition = if (m.accessFlags & AccVarargs) != 0 then paramTypes.length - 1 else -1
    val params = paramTypes.zipWithIndex.map { (t, i) =>
      val modifier =
        if i == varArgPosition then api.ParameterModifier.Repeated
        else api.ParameterModifier.Plain
      api.MethodParameter.of("", t, false, modifier)
    }
    val paramList = api.ParameterList.of(params, false)
    // Match ClassToAPI.uniqueConstructorName, which uses the binary (not canonical) class name.
    val name =
      if m.name.contains("<init>") then s"${binaryName.replace('.', ';')};init;"
      else m.name.getOrElse("")
    val acc = ClassToAPI.access(m.accessFlags, enclPkg)
    val mods = ClassToAPI.modifiers(m.accessFlags)
    val annots = syntheticAnnotations(
      "signature" -> m.attributes.find(_.isSignature).map(cf.stringValue).toList,
      "throws" -> exceptionNames(cf, m.attributes),
      "annotations" -> annotationTypeNames(cf, m.attributes)
    )
    val d = api.Def.of(name, acc, mods, annots, noTypeParameters, Array(paramList), returnType)
    (m.isStatic, d)
  end methodDef

  /** ACC_VARARGS (JVMS 4.6); java.lang.reflect.Modifier keeps its equivalent private. */
  private final val AccVarargs = 0x0080

  private val ObjectRef = ClassToAPI.reference("java.lang.Object")

  /** Parses a JVM field descriptor (JVMS 4.3.2) into an erased [[xsbti.api.Type]]. */
  private[inc] def parseFieldType(descriptor: String): api.Type = parseType(descriptor, 0)._1

  /** Parses a JVM method descriptor (JVMS 4.3.3) into (parameter types, return type), erased. */
  private[inc] def parseMethodType(descriptor: String): (Array[api.Type], api.Type) =
    val params = ArrayBuffer.empty[api.Type]
    var i = 1 // skip '('
    while i < descriptor.length && descriptor.charAt(i) != ')' do
      val (tpe, next) = parseType(descriptor, i)
      params += tpe
      i = next
    // `i` should sit on ')'; on a malformed/truncated descriptor fall back to a void return rather
    // than indexing past the end.
    val returnType =
      if i + 1 < descriptor.length then parseType(descriptor, i + 1)._1
      else ClassToAPI.primitive("void")
    (params.toArray, returnType)

  /**
   * Parses one field type starting at `i`, returning the type and the index just past it. Unknown
   * or truncated tokens (corrupt classfile, or a future descriptor form) degrade to an opaque
   * `Object` reference rather than throwing — this is a best-effort fallback that must never fail a
   * compile that would otherwise succeed.
   */
  private def parseType(descriptor: String, i: Int): (api.Type, Int) =
    if i >= descriptor.length then (ObjectRef, i + 1)
    else
      descriptor.charAt(i) match
        case 'B' => (ClassToAPI.primitive("byte"), i + 1)
        case 'C' => (ClassToAPI.primitive("char"), i + 1)
        case 'D' => (ClassToAPI.primitive("double"), i + 1)
        case 'F' => (ClassToAPI.primitive("float"), i + 1)
        case 'I' => (ClassToAPI.primitive("int"), i + 1)
        case 'J' => (ClassToAPI.primitive("long"), i + 1)
        case 'S' => (ClassToAPI.primitive("short"), i + 1)
        case 'Z' => (ClassToAPI.primitive("boolean"), i + 1)
        case 'V' => (ClassToAPI.primitive("void"), i + 1)
        case '[' =>
          val (elem, next) = parseType(descriptor, i + 1)
          (ClassToAPI.array(elem), next)
        case 'L' =>
          val semi = descriptor.indexOf(';', i)
          val end = if semi < 0 then descriptor.length else semi
          (ClassToAPI.reference(descriptor.substring(i + 1, end).replace('/', '.')), end + 1)
        case _ => (ObjectRef, i + 1)
end ClassfileToAPI
