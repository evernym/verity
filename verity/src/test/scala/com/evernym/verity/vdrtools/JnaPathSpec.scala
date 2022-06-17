package com.evernym.verity.vdrtools

import java.nio.file.Files

import com.evernym.verity.fixture.TempDir
import JnaPath._
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class JnaPathSpec extends AnyFreeSpec with Matchers with OptionValues with TempDir {
  protected var counter = 0
  def nextPropKey: String = {
    counter = counter + 1
    s"spec.jna.path.test.num$counter"
  }

  def setNextPropKeyTo(value: String): String = {
    val key = nextPropKey
    sys.props += key -> value
    key
  }

  "isRecognizedCmd" - {
    "should recognize sbt command as true" in {
      val testPropKey = setNextPropKeyTo("org.jetbrains.plugins.scala.testingSupport.scalaTest.ScalaTestRunner -s com.evernym.verity.libindy.JnaPathSpec -showProgressMessages true")
      isRecognizedCmd(propKey = testPropKey) shouldBe true

      val testPropKey2 = setNextPropKeyTo("/home/devin/.cache/sbt/boot/sbt-launch/1.6.2/sbt-launch-1.6.2.jar testOnly com.evernym.verity.vdrtools.JnaPathSpec")
      isRecognizedCmd(propKey = testPropKey2) shouldBe true
    }
    "should recognize testing environment" in {
      isRecognizedCmd() shouldBe true // since test run in environments, it should be true.
    }
  }

  "augmentJnaWithPaths" - {
    "should should add paths to prop correctly" in {
      augmentJnaWithPaths(Seq("/test/path"), propKey="test.jna.path.prop")
      sys.props.get("test.jna.path.prop").value shouldBe "/test/path"

      augmentJnaWithPaths(Seq("/test/path", "/test2/path"), propKey="test.jna.path.prop2")
      sys.props.get("test.jna.path.prop2").value shouldBe "/test/path:/test2/path"

      augmentJnaWithPaths(Seq("/test3/path", "/test2/path"), propKey="test.jna.path.prop2")
      sys.props.get("test.jna.path.prop2").value shouldBe "/test3/path:/test/path:/test2/path"

      sys.props += "test.jna.path.prop4" -> "/existing/test"
      augmentJnaWithPaths(Seq("/test/path"), propKey="test.jna.path.prop4")
      sys.props.get("test.jna.path.prop4").value shouldBe "/test/path:/existing/test"
    }
    "should should handle edge cases" in {
      augmentJnaWithPaths(Seq(), propKey = "test.jna.edge.path.prop")
      sys.props.get("test.jna.edge.path.prop").value shouldBe ""

      augmentJnaWithPaths(null, propKey = "test.jna.edge.path.prop")
      sys.props.get("test.jna.edge.path.prop").value shouldBe ""
    }
  }

  "findJnaPaths" - {
    "should find all expected paths" in {
      val baseDir = tempDir
      Files.createDirectories(baseDir.resolve("verity/target/shared-libs/libs"))
      Files.createDirectories(baseDir.resolve("integration-test/target/shared-libs/libs"))
      Files.createDirectories(baseDir.resolve("target/"))
      Files.createDirectories(baseDir.resolve("verity/src/test"))

      val cwdPropKey = "test.jna.search.path.prop"
      sys.props += cwdPropKey -> baseDir.resolve("verity/src/test").toString
      val paths = findJnaPaths(cwdPropKey = cwdPropKey)

      paths.length shouldBe 2
      paths.count(_.endsWith("verity/target/shared-libs/libs")) shouldBe 1
      paths.count(_.endsWith("integration-test/target/shared-libs/libs")) shouldBe 1
    }
    "should not find non-existent paths" in {
      val baseDir = tempDir
      Files.createDirectories(baseDir.resolve("verity/target/shared-libs/libs"))
      Files.createDirectories(baseDir.resolve("target/"))
      Files.createDirectories(baseDir.resolve("verity/src/test"))

      val cwdPropKey = "test.jna.search.path.prop2"
      sys.props += cwdPropKey -> baseDir.resolve("verity/src/test").toString
      val paths = findJnaPaths(cwdPropKey = cwdPropKey)

      paths.length shouldBe 1
      paths.count(_.endsWith("verity/target/shared-libs/libs")) shouldBe 1
    }
  }
}

