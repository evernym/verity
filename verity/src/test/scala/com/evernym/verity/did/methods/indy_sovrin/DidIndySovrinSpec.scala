package com.evernym.verity.did.methods.indy_sovrin

import com.evernym.verity.did.exception.InvalidDidIndySovrinFormatException
import com.evernym.verity.testkit.BasicSpec


class DidIndySovrinSpec
  extends BasicSpec {

  val didString = "did:indy:sovrin:2wJPyULfLLnYTEFYzByfUR"

  "When a did:indy:sovrin object is created from a valid did string" - {
    "the resulting did:indy:sovrin should be correct" in {
      val testDid: DIDIndySovrin = new DIDIndySovrin(didString)
      testDid.toString() shouldBe didString
      testDid.methodIdentifier.toString shouldBe "sovrin:2wJPyULfLLnYTEFYzByfUR"
      testDid.method shouldBe "indy"
    }
  }

  "When a did:indy:sovrin object is created from an invalid did string" - {
    "should throw appropriate error" in {
      val ex = intercept[InvalidDidIndySovrinFormatException] {
        new DIDIndySovrin("invalid-did-string")
      }
      ex.getMessage shouldBe "unable to parse received string: invalid-did-string into valid indy sovrin did"
    }
  }
}