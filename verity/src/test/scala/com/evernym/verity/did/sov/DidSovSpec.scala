package com.evernym.verity.did.sov

import com.evernym.verity.did.methods.DIDSov
import com.evernym.verity.testkit.BasicSpec

class DidSovSpec extends BasicSpec {
  val didString = "did:sov:2wJPyULfLLnYTEFYzByfUR"

  "When a did:sov object is created from a string" - {
    "the resulting did:sov should be correct" - {
      val testDid: DIDSov = new DIDSov(didString)

      testDid.toString() shouldBe didString
      testDid.identifier shouldBe "2wJPyULfLLnYTEFYzByfUR"
      testDid.method shouldBe "sov"
    }
  }
}