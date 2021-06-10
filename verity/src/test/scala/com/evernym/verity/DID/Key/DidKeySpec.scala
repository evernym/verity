package com.evernym.verity.DID.Key

import com.evernym.verity.DID.DID
import com.evernym.verity.DID.Methods.DIDKey
import com.evernym.verity.DID.DidException.InvalidDidKeyFormatException
import com.evernym.verity.protocol.engine.VerKey
import com.evernym.verity.util.Base58Util
import com.evernym.verity.testkit.BasicSpec



class DidKeySpec extends BasicSpec {
  val verkey = "87shCEvKAWw6JncoirStGxkRptVriLeNXytw9iRxpzGY"
  val didkey = "did:key:z2DXXwXqC5VKhhDVLCoZSX98Gr33w1TGfNnA3y192dsDjbv"

  "When a did:key object is created from a verkey" - {
    "the resulting did:key should be correct" - {
      val testdidkey: DIDKey = new DIDKey(verkey)

      testdidkey.toString() shouldBe didkey
    }
  }
}
