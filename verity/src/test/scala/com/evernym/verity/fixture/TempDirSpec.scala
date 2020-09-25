package com.evernym.verity.fixture

import java.io.FileWriter
import java.nio.file.Paths

import com.evernym.verity.testkit.BasicSpec
import scala.io.Source

class TempDirSpec extends BasicSpec with TempDir {
  override def deleteFiles = false
  "TempDir" - {
    "should create a suiteTempDir" in {
      suiteTempDir.toFile.exists() shouldBe true
      val t = 12
    }
    "should create a tempDir" in {
      tempDir.toFile.exists() shouldBe true
    }

    "tempDir should be writable" in {
      val testData = "TEST"
      val fileName = "test.txt"
      val writer = new FileWriter(tempDir.resolve(fileName).toFile)
      writer.write(testData)
      writer.close()


      val readSource = Source.fromFile(tempDir.resolve(fileName).toFile)
      val readData = readSource.mkString
      readSource.close()
      readData shouldBe testData
    }

    "findTarget handle possible input" in {
      def tryFindTarget(path: String, target: Option[String]) = {
        val p = if(path == null) null else Paths.get(path)
        val e = target.map(Paths.get(_))
        TempDir.findTarget(p) shouldBe e
      }

      tryFindTarget(
        null,
        None
      )

      tryFindTarget(
        "",
        None
      )

      tryFindTarget(
        "/home/sdf/devel/agency/integration-tests/target/streams/compile/compile",
        Some("/home/sdf/devel/agency/integration-tests/target")
      )

      tryFindTarget(
        "/home/sdf/devel/agency/integration-tests/target/streams/compile/compile",
        Some("/home/sdf/devel/agency/integration-tests/target")
      )

      tryFindTarget(
        "/home/sdf/devel/agency/integration-tests/target",
        Some("/home/sdf/devel/agency/integration-tests/target")
      )

      tryFindTarget(
        "/home/sdf/devel/agency/integration-tests/target/",
        Some("/home/sdf/devel/agency/integration-tests/target")
      )

      tryFindTarget(
        "/home/sdf/devel/agency/integration-tests/streams/compile/compile",
        None
      )


    }
  }
}
