package com.evernym.verity.did

import com.evernym.verity.did.methods.UnqualifiedDID
import com.evernym.verity.did.methods.indy_sovrin.DIDIndySovrin
import com.evernym.verity.did.methods.key.DIDKey
import com.evernym.verity.did.methods.sov.DIDSov
import com.evernym.verity.testkit.BasicSpec

class DidMethodSpec
  extends BasicSpec {

  "when an invalid did string" - {
    "is asked to convert to a did method" - {
      "should throw error" in {
        //TODO: add few more invalid entries if possible
        val invalidDidStrs = List("did:abc", "sov:abc", "12345:indy")
        invalidDidStrs.foreach { did =>
          intercept[RuntimeException] {
            toDIDMethod(did)
          }
        }
      }
    }
  }

  "when an unqualified did string" - {
    "is asked to convert to a did method" - {
      "should be converted to Unqualified did" in {
        //TODO:
        // 1. confirm if all the given entries are "valid unqualified dids"
        // 2. add few more valid entries if possible
        val unqualifiedDidStrs = List("did", "sov", "12345")
        unqualifiedDidStrs.foreach { did =>
          val didMethod = toDIDMethod(did)
          didMethod shouldBe a[UnqualifiedDID]
        }
      }
    }
  }

  "when a valid did:key string" - {
    "is asked to convert to a did method" - {
      "should be successful" in {
        //TODO: add few more valid did:key entries is possible
        val didKeyStrs = List(
          "did:key:123"
        )
        didKeyStrs.foreach { did =>
          val didMethod = toDIDMethod(did)
          didMethod shouldBe a[DIDKey]
        }
      }
    }
  }

  "when a valid did:sov string" - {
    "is asked to convert to a did method" - {
      "should be successful" in {
        //TODO: add few more valid did:sov entries if possible
        val didKeyStrs = List(
          "did:sov:2wJPyULfLLnYTEFYzByfUR"
        )
        didKeyStrs.foreach { did =>
          val didMethod = toDIDMethod(did)
          didMethod shouldBe a[DIDSov]
        }
      }
    }
  }

  "when a valid did:indy:sovrin string" - {
    "is asked to convert to a did method" - {
      "should be successful" in {
        //TODO: add any other possible valid did:indy:sovrin entries if possible
        val didKeyStrs = List(
          "did:indy:sovrin:2wJPyULfLLnYTEFYzByfUR",
          "did:indy:sovrin:stage:2wJPyULfLLnYTEFYzByfUR",
          "did:indy:sovrin:builder:2wJPyULfLLnYTEFYzByfUR"
        )
        didKeyStrs.foreach { did =>
          val didMethod = toDIDMethod(did)
          didMethod shouldBe a[DIDIndySovrin]
        }
      }
    }
  }

}
