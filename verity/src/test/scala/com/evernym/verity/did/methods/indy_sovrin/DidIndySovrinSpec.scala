package com.evernym.verity.did.methods.indy_sovrin

import com.evernym.verity.did.exception.InvalidDidIndySovrinFormatException
import com.evernym.verity.testkit.BasicSpec


class DidIndySovrinSpec
  extends BasicSpec {

  "When a did:indy:sovrin object is created from a valid did string" - {
    "the resulting did:indy:sovrin should be correct" in {
      val indySovrinDid = "did:indy:sovrin:2wJPyULfLLnYTEFYzByfUR"
      val testDid: DIDIndySovrin = new DIDIndySovrin(indySovrinDid)
      testDid.toString() shouldBe indySovrinDid
      testDid.methodIdentifier.toString shouldBe "sovrin:2wJPyULfLLnYTEFYzByfUR"
      testDid.methodIdentifier.namespace shouldBe "indy:sovrin"
      testDid.methodIdentifier.namespaceIdentifier shouldBe "2wJPyULfLLnYTEFYzByfUR"
      testDid.method shouldBe "indy"
    }
  }

  "When a did:indy:sovrin:stage object is created from a valid did string" - {
    "the resulting did:indy:sovrin:stage should be correct" in {
      val indySovrinStagingDid = "did:indy:sovrin:stage:2wJPyULfLLnYTEFYzByfUR"
      val testDid: DIDIndySovrin = new DIDIndySovrin(indySovrinStagingDid)
      testDid.toString() shouldBe indySovrinStagingDid
      testDid.methodIdentifier.toString shouldBe "sovrin:stage:2wJPyULfLLnYTEFYzByfUR"
      testDid.methodIdentifier.namespace shouldBe "indy:sovrin:stage"
      testDid.methodIdentifier.namespaceIdentifier shouldBe "2wJPyULfLLnYTEFYzByfUR"
      testDid.method shouldBe "indy"
    }
  }

  "When a did:indy:sovrin:builder object is created from a valid did string" - {
    "the resulting did:indy:sovrin:builder should be correct" in {
      val indySovrinBuilderDid = "did:indy:sovrin:builder:2wJPyULfLLnYTEFYzByfUR"
      val testDid: DIDIndySovrin = new DIDIndySovrin(indySovrinBuilderDid)
      testDid.toString() shouldBe indySovrinBuilderDid
      testDid.methodIdentifier.toString shouldBe "sovrin:builder:2wJPyULfLLnYTEFYzByfUR"
      testDid.methodIdentifier.namespace shouldBe "indy:sovrin:builder"
      testDid.methodIdentifier.namespaceIdentifier shouldBe "2wJPyULfLLnYTEFYzByfUR"
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