package com.evernym.verity.util

import java.nio.file.Paths

import com.evernym.verity.fixture.TempDir
import com.evernym.verity.testkit.BasicSpec


class FileUtilSpec extends BasicSpec with TempDir {

  "dirIsEmpty" - {
    "empty dir should be true" in {
      FileUtil.dirIsEmpty(tempDir) shouldBe true
      FileUtil.dirNotEmpty(tempDir) shouldBe false
    }

    "non-existent dir should return false" in {
      FileUtil.dirIsEmpty(Paths.get("/asdfa/asdf/asdf")) shouldBe false
      FileUtil.dirNotEmpty(Paths.get("/asdfa/asdf/asdf")) shouldBe true
    }

    "non-empty dir should be false" in {
      tempFile("Foo")
      FileUtil.dirIsEmpty(tempDir) shouldBe false
      FileUtil.dirNotEmpty(tempDir) shouldBe true
    }
  }
}
