package com.evernym.verity.did.methods.sov

import com.evernym.verity.did.exception.InvalidDidSovFormatException
import com.evernym.verity.testkit.BasicSpec


class DidSovSpec
  extends BasicSpec {

  val didString = "did:sov:2wJPyULfLLnYTEFYzByfUR"

  "When a did:sov object is created from a valid did string" - {
    "the resulting did:sov should be correct" in {
      val testDid: DIDSov = new DIDSov(didString)
      testDid.toString() shouldBe didString
      testDid.methodIdentifier.toString shouldBe "2wJPyULfLLnYTEFYzByfUR"
      testDid.method shouldBe "sov"
    }
  }

  "When a did:sov object is created from an invalid did string" - {
    "should throw appropriate error" in {
      val ex = intercept[InvalidDidSovFormatException] {
        new DIDSov("invalid-did-string")
      }
      ex.getMessage shouldBe "unable to parse received string: invalid-did-string into valid sovrin did"
    }
  }
}