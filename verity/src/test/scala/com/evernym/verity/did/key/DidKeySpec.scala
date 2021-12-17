package com.evernym.verity.did.key

import com.evernym.verity.did.methods.DIDKey
import com.evernym.verity.did.exception.InvalidDidKeyFormatException
import com.evernym.verity.util.Base58Util
import com.evernym.verity.testkit.BasicSpec



class DidKeySpec extends BasicSpec {
  val verkey = "87shCEvKAWw6JncoirStGxkRptVriLeNXytw9iRxpzGY"
  val didkey = "did:key:z6Mkma8jnVAkW4RZRHTWQRQj84JReTmi8DtjDzoryzPykD3v"

  "When a did:key object is created from a verkey" - {
    "the resulting did:key should be correct" in {
      val testdidkey: DIDKey = new DIDKey(verkey)

      testdidkey.toString() shouldBe didkey
    }
  }
}
