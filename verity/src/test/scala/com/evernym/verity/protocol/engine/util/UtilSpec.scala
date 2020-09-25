package com.evernym.verity.protocol.engine.util

import com.evernym.verity.util.Base58Util
import com.evernym.verity.util.Util._
import com.google.common.base.Charsets
import com.google.common.io.BaseEncoding
import com.evernym.verity.Exceptions.InvalidValueException
import com.evernym.verity.protocol.engine.DID

import com.evernym.verity.testkit.BasicSpec



class UtilSpec extends BasicSpec {

  val beforeBase58Encoded = "iIoO0"
  val afterBase58Encoded = "Csxge31"
  val base64EncodedString = BaseEncoding.base64().encode(beforeBase58Encoded.getBytes(Charsets.UTF_8))
  val base64EncodedStringDecoded = Base58Util.decode(base64EncodedString)
  "Base58" - {
    "encoding a string" - {
      "should result in the expected Base58 encoded string" in {
        val encoded = Base58Util.encode(beforeBase58Encoded.getBytes())
        encoded shouldBe afterBase58Encoded
      }

      "should result in the same result every time" in {
        val base = Base58Util.encode(beforeBase58Encoded.getBytes())
        val base2 = Base58Util.encode(beforeBase58Encoded.getBytes())
        base shouldBe base2
      }
    }

    "decoding a valid Base58 encoded string" - {
      "should equal the string before it was Base58 encoded" in {
        val decoded = (Base58Util.decode(afterBase58Encoded).get.map(_.toChar)).mkString
        decoded shouldBe beforeBase58Encoded
      }
    }

    "decoding an emptry string" - {
      "should throw an IllegalArgumentException" in {
        val caught = intercept[IllegalArgumentException] {
          Base58Util.decode("").get
        }
        caught.getMessage shouldBe "requirement failed: Empty input for Base58.decode"
      }
    }

    "decoding a Base64 encoded string" - {
      "should throw an AssertionError" in {
        val caught = intercept[AssertionError] {
          Base58Util.decode(base64EncodedString).get
        }
        caught.getMessage shouldBe "assertion failed: Wrong char in Base58 string"
      }
    }
  }

  val validDID: DID = "Rgj7LVEonrMzcRC1rhkx76"
  val invalidDID: DID = "Rgj7LVEonrMzcRC1rhkx7="
  val shortDID: DID = "Rgj7LVEonr"
  val emptyDID: DID = ""
  "DID" - {
    "check on a valid DID" - {
      "should succeed without throwing an exception" in {
        try { checkIfDIDIsValid(validDID) } catch {
          case t: Throwable => fail(t.getMessage)
        }
      }
    }
    "check on an invalid DID" - {
      "should succeed without throwing an exception" in {
        val caught: InvalidValueException = intercept[InvalidValueException] {
          checkIfDIDIsValid(invalidDID)
        }
        caught.getMessage shouldBe s"DID (${invalidDID}) is not a Base58 string. Reason: assertion failed: Wrong char in Base58 string"
      }
    }
    "check on a short DID" - {
      "should throw an exception" in {
        val caught: InvalidValueException = intercept[InvalidValueException] {
          checkIfDIDIsValid(shortDID)
        }
        caught.getMessage shouldBe s"actual length of DID (${shortDID}) is: ${shortDID.length}, expected length is: Range 21 to 23"
      }
    }
    "check on an empty DID" - {
      "should throw an exception" in {
        val caught: InvalidValueException = intercept[InvalidValueException] {
          checkIfDIDIsValid(emptyDID)
        }
        caught.getMessage shouldBe s"actual length of DID (${emptyDID}) is: ${emptyDID.length}, expected length is: Range 21 to 23"
      }
    }
  }
}
