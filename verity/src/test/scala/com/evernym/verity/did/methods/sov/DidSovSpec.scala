package com.evernym.verity.did.methods.sov

import com.evernym.verity.did.exception.InvalidDidSovFormatException
import com.evernym.verity.testkit.BasicSpec


class DidSovSpec
  extends BasicSpec {

  "When a did:sov object is created from a valid did string" - {
    "the resulting did:sov should be correct" in {
      val validDidSovStrings = List("did:sov:2wJPyULfLLnYTEFYzByfUR")
      validDidSovStrings.foreach { didStr =>
        val testDid: DIDSov = new DIDSov(didStr)
        val expectedIdentifier = didStr.split(":").last
        testDid.toString() shouldBe didStr
        testDid.methodIdentifier.toString shouldBe expectedIdentifier
        testDid.method shouldBe "sov"
      }
    }
  }

  "When a did:sov object is created from an invalid did string" - {
    "should throw appropriate error" in {
      val invalidDidSovStrings = List("invalid-did-string")
      invalidDidSovStrings.foreach { didStr =>
        val ex = intercept[InvalidDidSovFormatException] {
          new DIDSov(didStr)
        }
        ex.getMessage shouldBe s"unable to parse received string: $didStr into valid sovrin did"
      }
    }
  }
}