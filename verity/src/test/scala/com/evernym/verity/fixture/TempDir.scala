package com.evernym.verity.fixture

import java.io.File
import java.nio.file.{Files, Path, Paths}

import com.evernym.verity.util.FileUtil
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Suite, SuiteMixin}

trait TempDir extends SuiteMixin with BeforeAndAfterEach with BeforeAndAfterAll {
  this: Suite =>
  val suiteTempDir: Path = findSuiteDir

  final def tempDir: Path = curTempDir

  def tempFile(prefix: String, suffix: String = ".tmp"): File = {
    Files.createTempFile(tempDir, prefix, suffix).toFile
  }

  private def findSuiteDir: Path = {
    val classFileStr = this.getClass.getProtectionDomain.getCodeSource.getLocation.getPath
    val t= TempDir
      .findSuiteTargetDir(classFileStr, this.suiteName)
      .getOrElse(
        TempDir.findSuiteTempDir(this.suiteName)
      )
    t
  }

  lazy private val createSymlink = FileUtil.tryCreateSymlink(suiteTempDir.getParent) _

  private var curTempDir: Path = _
  private var testCount: Int = 0



  def deleteFiles: Boolean = false

  private def deleteRecursive(file: File): Unit = {
    if(file.isDirectory) {
      file.listFiles().foreach(deleteRecursive)
    }
    file.delete()
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    createSymlink("last-suite", suiteTempDir)
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    curTempDir = Files.createTempDirectory(suiteTempDir, s"test-$testCount-")
    createSymlink("last-test", curTempDir)
    testCount += 1
  }

  override def afterEach(): Unit = {
    if (FileUtil.dirIsEmpty(tempDir)) deleteRecursive(tempDir.toFile)
    super.afterEach()
  }

  override protected def afterAll(): Unit = {
    // Might be useful to not delete if Suite does not pass, not sure how to assert that
    if(deleteFiles) deleteRecursive(suiteTempDir.toFile)
    super.afterAll()
  }
}

object TempDir {
  def createTargetDir(targetDir: Path): Path = {
    val create = targetDir.resolve("scalatest-runs")
    if(Files.notExists(create)) {
      Files.createDirectory(create)
    }
    create
  }

  def findSuiteTargetDir(classFileStr: String, suiteName: String): Option[Path] = {
    val classFilePath = Paths.get(classFileStr)
    val target = TempDir.findTarget(classFilePath)

    target
      .map {p =>
        if(!p.toFile.isDirectory) throw new Exception(s"TempDir requires target folder to be a directory, '$p' is not a directory")
        p
      }
      .map { p =>
        Files.createTempDirectory(createTargetDir(p), s"$suiteName-")
      }
  }

  def findSuiteTempDir(suiteName: String): Path = {
    Files.createTempDirectory(s"$suiteName-")
  }

  def findTarget(path: Path): Option[Path] ={
    Option(path).flatMap{ p =>
      if ( p.endsWith("target") ) Some(p)
      else findTarget(p.getParent)
    }
  }
}



