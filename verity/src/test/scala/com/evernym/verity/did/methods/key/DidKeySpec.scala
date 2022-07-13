package com.evernym.verity.did.methods.key

import com.evernym.verity.did.exception.InvalidDidKeyFormatException
import com.evernym.verity.testkit.BasicSpec



class DidKeySpec
  extends BasicSpec {

  val verKey = "87shCEvKAWw6JncoirStGxkRptVriLeNXytw9iRxpzGY"
  val didKey = "did:key:z6Mkma8jnVAkW4RZRHTWQRQj84JReTmi8DtjDzoryzPykD3v"

  "When a did:key object is created from a valid verKey" - {
    "the resulting did:key should be correct" in {
      val testDidKey = new DIDKey(verKey)
      testDidKey.toString() shouldBe didKey
    }
  }

  "When a did:key object is created with fully qualified did:key itself" - {
    "the resulting did:key should be correct" in {
      val testDidKey = new DIDKey(didKey)
      testDidKey.toString() shouldBe didKey
    }
  }

  "When a did:key object is created from an invalid verKey" - {
    "should throw appropriate error" in {
      val ex = intercept[InvalidDidKeyFormatException] {
        new DIDKey("invalid-ver-key")
      }
      ex.getMessage shouldBe "unable to parse received string: invalid-ver-key"
    }
  }
}
