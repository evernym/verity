package com.evernym.verity.vdr

import com.evernym.verity.testkit.BasicSpec

class FQIdentifierSpec
  extends BasicSpec {

  "FQIdentifier" - {

    "when created for valid DIDs" - {
      "should be successful" in {
        val fqId = FQIdentifier("did:sov:Wynd2Z2yJBAqKcx38EfHLJ", List("sov"))
        fqId.vdrNamespace shouldBe "sov"
        fqId.methodIdentifier shouldBe "Wynd2Z2yJBAqKcx38EfHLJ"
      }
    }

    "when created for valid schema ids" - {
      "should be successful" in {
        val fqId = FQIdentifier("schema:sov:did:sov:QiFENr5Cajphh2y1n6Fz98:2:name:1.0", List("sov"))
        fqId.vdrNamespace shouldBe "sov"
        fqId.methodIdentifier shouldBe "did:sov:QiFENr5Cajphh2y1n6Fz98:2:name:1.0"
      }
    }

    "when created for valid creddef ids" - {
      "should be successful" in {
        val fqId = FQIdentifier("creddef:sov:did:sov:QiFENr5Cajphh2y1n6Fz98:3:CL:NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0:Tag1", List("sov"))
        fqId.vdrNamespace shouldBe "sov"
        fqId.methodIdentifier shouldBe "did:sov:QiFENr5Cajphh2y1n6Fz98:3:CL:NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0:Tag1"
      }
    }
  }
}
