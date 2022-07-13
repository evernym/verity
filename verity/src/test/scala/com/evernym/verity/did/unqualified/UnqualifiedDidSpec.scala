package com.evernym.verity.did.unqualified

import com.evernym.verity.did.methods.UnqualifiedDID
import com.evernym.verity.testkit.BasicSpec

class UnqualifiedDidSpec
  extends BasicSpec {

  "UnqualifiedDID" - {
    "when given an unqualified did string" - {
      "should result into an UnqualifiedDID" in {
        val didString = "2wJPyULfLLnYTEFYzByfUR"
        val testDid: UnqualifiedDID = new UnqualifiedDID(didString)
        testDid.toString shouldBe didString
        testDid.methodIdentifier.toString shouldBe "2wJPyULfLLnYTEFYzByfUR"
        testDid.method shouldBe "unqualified"
      }
    }

    "when given a fully qualified did string" - {
      "should result into an error" in {
        val didStr = "did:sov:2wJPyULfLLnYTEFYzByfUR"
        val ex = intercept[RuntimeException] {
          new UnqualifiedDID(didStr)
        }
        ex.getMessage shouldBe s"invalid unqualified DID: $didStr"
      }
    }
  }
}
