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
package classfile

import java.io.{ File, InputStream }
import java.net.URLClassLoader
import javax.tools.{ StandardLocation, ToolProvider }
import java.nio.file.{ Files, Path }

import sbt.io.IO
import sbt.internal.util.ConsoleLogger
import xsbti.api.DependencyContext.*
import xsbti.{ AnalysisCallback, BasicVirtualFileRef, TestCallback, VirtualFile, VirtualFileRef }
import xsbti.TestCallback.ExtractedClassDependencies
import xsbti.compile.SingleOutput

import scala.jdk.CollectionConverters.*

object JavaCompilerForUnitTesting:
  private class TestVirtualFile(p: Path) extends BasicVirtualFileRef(p.toString) with VirtualFile:
    override def contentHash(): Long = sbt.io.Hash(p.toFile).hashCode.toLong
    override def sizeBytes: Long = Files.size(p)
    override lazy val contentHashStr: String = contentHash().toHexString
    override def input(): InputStream = Files.newInputStream(p)

  def extractDependenciesFromSrcs(srcs: (String, String)*): ExtractedClassDependencies =
    val (_, testCallback) = compileJavaSrcs(srcs*)((_, _, named, _) => extractParents(named))

    val memberRefDeps = testCallback.classDependencies
      .collect({
        case (target, src, DependencyByMemberRef) => (src, target)
      })
      .toSeq
    val inheritanceDeps = testCallback.classDependencies
      .collect({
        case (target, src, DependencyByInheritance) => (src, target)
      })
      .toSeq
    val localInheritanceDeps = testCallback.classDependencies
      .collect({
        case (target, src, LocalDependencyByInheritance) => (src, target)
      })
      .toSeq
    ExtractedClassDependencies.fromPairs(memberRefDeps, inheritanceDeps, localInheritanceDeps)

  def compileJavaSrcs(srcs: (String, String)*)(
      readAPI: (
          AnalysisCallback,
          VirtualFileRef,
          Seq[(String, ClassFile)],
          ClassfileToAPIResolve
      ) => Set[(String, String)]
  ): (Seq[VirtualFile], TestCallback) =
    IO.withTemporaryDirectory { temp =>
      val srcFiles0 = srcs.map {
        case (fileName, src) => prepareSrcFile(temp, fileName, src)
      }
      val srcFiles: List[VirtualFile] =
        srcFiles0.toList.map(x => new TestVirtualFile(x.toPath): VirtualFile)
      val analysisCallback = new TestCallback
      val classesDir = new File(temp, "classes")
      classesDir.mkdir()

      val compiler = ToolProvider.getSystemJavaCompiler()
      val fileManager = compiler.getStandardFileManager(null, null, null)
      fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Seq(classesDir).asJava)
      val compilationUnits = fileManager.getJavaFileObjectsFromFiles(srcFiles0.asJava)
      compiler.getTask(null, fileManager, null, null, null, compilationUnits).call()
      fileManager.close()

      val classesFinder = sbt.io.PathFinder(classesDir) ** "*.class"
      val classFiles = classesFinder.get().map(_.toPath)

      val classloader = new URLClassLoader(Array(classesDir.toURI.toURL))

      val logger = ConsoleLogger()
      // logger.setLevel(sbt.util.Level.Debug)

      // we pass extractParents as readAPI. In fact, Analyze expect readAPI to do both things:
      // - extract api representation out of the class files (and saved it via a side effect)
      // - extract all base classes.
      // we extract just parents as this is enough for testing

      val output = new SingleOutput:
        override def getOutputDirectoryAsPath: Path = classesDir.toPath
        override def getOutputDirectory: File = getOutputDirectoryAsPath.toFile
      JavaAnalyze(classFiles, srcFiles, logger, output, finalJarOutput = None)(
        analysisCallback,
        classloader,
        readAPI(analysisCallback, _, _, _)
      )
      (srcFiles, analysisCallback)
    }

  /** Compiles the given Java sources to `outputDir`, with `classpath` available at compile time. */
  def compileJava(files: Seq[File], outputDir: File, classpath: Seq[File]): Unit =
    val compiler = ToolProvider.getSystemJavaCompiler()
    val fileManager = compiler.getStandardFileManager(null, null, null)
    fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Seq(outputDir).asJava)
    if classpath.nonEmpty then
      fileManager.setLocation(StandardLocation.CLASS_PATH, classpath.asJava)
    val units = fileManager.getJavaFileObjectsFromFiles(files.asJava)
    compiler.getTask(null, fileManager, null, null, null, units).call()
    fileManager.close()

  /**
   * Runs [[JavaAnalyze]] over the class files already present in `classesDir`, mapping them back to
   * the given `srcFiles`. Unlike [[compileJavaSrcs]] this does not compile, so the caller can stage
   * the class files (e.g. compile against one classpath, then swap a dependency) before analysis.
   */
  def analyze(
      classesDir: File,
      srcFiles: Seq[File],
      readClassfileAPI: (
          AnalysisCallback,
          VirtualFileRef,
          Seq[(String, ClassFile)],
          ClassfileToAPIResolve
      ) => Unit = (_, _, _, _) => ()
  ): TestCallback =
    val srcs: List[VirtualFile] = srcFiles.toList.map(f => new TestVirtualFile(f.toPath))
    val analysisCallback = new TestCallback
    val classFiles = (sbt.io.PathFinder(classesDir) ** "*.class").get().map(_.toPath)
    val classloader = new URLClassLoader(Array(classesDir.toURI.toURL), null)
    val output = new SingleOutput:
      override def getOutputDirectoryAsPath: Path = classesDir.toPath
      override def getOutputDirectory: File = classesDir
    JavaAnalyze(classFiles, srcs, ConsoleLogger(), output, finalJarOutput = None)(
      analysisCallback,
      classloader,
      (source, named, resolve) =>
        readClassfileAPI(analysisCallback, source, named, resolve)
        extractParents(named)
    )
    analysisCallback
  end analyze

  private def prepareSrcFile(baseDir: File, fileName: String, src: String): File =
    val srcFile = new File(baseDir, fileName)
    IO.write(srcFile, src)
    srcFile

  /** Inheritance edges from the class files, as `AnalyzingJavaCompiler.readAPI` returns them. */
  private val extractParents: Seq[(String, ClassFile)] => Set[(String, String)] = named =>
    named.iterator.flatMap {
      case (_, cf) =>
        (cf.superClassName +: cf.interfaceNames.toIndexedSeq)
          .filter(_.nonEmpty)
          .map(cf.className -> _)
    }.toSet

  /** A supertype resolver, as `JavaAnalyze` hands `readAPI` one. */
  type ClassfileToAPIResolve = String => Option[ClassFile]
end JavaCompilerForUnitTesting
