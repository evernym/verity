package com.evernym.verity.did.unqualified

import com.evernym.verity.did.methods.UnqualifiedDID
import com.evernym.verity.testkit.BasicSpec

class UnqualifiedDidSpec extends BasicSpec {
  val didString = "2wJPyULfLLnYTEFYzByfUR"

  "When an unqualified did object is created from a string" - {
    "the resulting did should be correct" - {
      val testDid: UnqualifiedDID = new UnqualifiedDID(didString)

      testDid.toString() shouldBe didString
      testDid.identifier shouldBe "2wJPyULfLLnYTEFYzByfUR"
      testDid.method shouldBe "unqualified"
    }
  }
}
