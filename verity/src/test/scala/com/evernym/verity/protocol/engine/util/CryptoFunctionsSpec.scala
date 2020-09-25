package com.evernym.verity.protocol.engine.util

import java.security.MessageDigest

import com.evernym.verity.protocol.engine.util.CryptoFunctions.{byteArray2RichBytes, safeHash, safeHashTrunc16}
import org.apache.commons.codec.binary.{Hex, StringUtils}
import org.apache.commons.codec.digest.{DigestUtils, MessageDigestAlgorithms}
import com.evernym.verity.testkit.BasicSpec




class CryptoFunctionsSpec extends BasicSpec {

  sealed trait HashAlgorithm {
    def libName: String
    def truncateBytes: Option[Int] = None
  }

  /** It's important to use standard configurations; those defined here should be standard
    */
  object HashAlgorithm {

    object SHA256 extends HashAlgorithm {
      val libName: String = MessageDigestAlgorithms.SHA_256
    }

    object SHA256_trunc16 extends HashAlgorithm {
      val libName: String = MessageDigestAlgorithms.SHA_256
      override val truncateBytes = Some(16)
    }

  }

  def oldSafeHash(algo: HashAlgorithm)(strs: String*): Array[Byte] = {
    val md: MessageDigest = DigestUtils.getSha256Digest
    strs map StringUtils.getBytesUtf8 map DigestUtils.getDigest(algo.libName).digest foreach md.update
    algo.truncateBytes match {
      case Some(n) => md.digest.truncate(n)
      case None => md.digest
    }
  }

  "CryptoFunctions.safeHash" - {
    "should have the same result as oldSafeHash" in {
      safeHash("Hi", "Hello", "World") shouldBe oldSafeHash(HashAlgorithm.SHA256)("Hi", "Hello", "World").hex
    }
  }

  "CryptoFunctions.safeHashTrunc16" - {
    "should have the same result as oldSafeHash(SHA256_trunc16)" in {
      safeHashTrunc16("Hi", "Hello", "World") shouldBe oldSafeHash(HashAlgorithm.SHA256_trunc16)("Hi", "Hello", "World").hex
    }
  }

  "Hashing a list of strings" - {
    "is equivalent to a hash of the concatenated hashes of each string" in {

      val habc = DigestUtils.sha256("abc")
      val hdef = DigestUtils.sha256("def")
      val hghi = DigestUtils.sha256("ghi")

      habc.hex should be ("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
      hdef.hex should be ("cb8379ac2098aa165029e3938a51da0bcecfc008fd6795f401178647f96c5b34")
      hghi.hex should be ("50ae61e841fac4e8f9e40baf2ad36ec868922ea48368c18f9535e47db56dd7fb")

      val concatHashes = habc ++ hdef ++ hghi
      val combinedHash = DigestUtils.sha256( concatHashes )

      combinedHash.hex should be ("c5f0581d535a366dc1bce1390f787caf5d93a4a1493cd0cdedfc86d29bfd8848")

      val truncatedHash = combinedHash.truncate(16)

      val base = safeHashTrunc16("abc", "def", "ghi")

      base should be (truncatedHash.hex)
    }

    "should result in the same result every time" in {
      val base = safeHashTrunc16("abc", "def", "ghi")
      val base2 = safeHashTrunc16("abc", "def", "ghi")
      base shouldBe base2
    }

    "should NOT be the same as the hash of the same items in a different order" in {
      val base = safeHashTrunc16("abc", "def", "ghi")
      val reordered = safeHashTrunc16("def", "abc", "ghi")
      base should not be reordered
    }

    "should NOT be the same as the hash of the concatenated items" in {
      val base = safeHashTrunc16("abc", "def", "ghi")
      val concat = safeHashTrunc16("abc" + "def" + "ghi")
      base should not be concat
    }

    "should NOT be the same as the hash of the items with elements shifted" in {
      val base = safeHashTrunc16("abc", "def", "ghi")
      val shifted = safeHashTrunc16("abcd", "ef", "ghi")
      base should not be shifted
    }
  }

  "Both versions of overloaded sha256" - {
    "should return the same result" in {
      val str = "Hello, World"
      CryptoFunctions.sha256(str) shouldBe CryptoFunctions.sha256(str.getBytes("UTF-8"))
    }
  }

  "RichBytes.hex" - {
    "should convert bytes to hex the same as Hex.encodeHexString" in { // Since we can't use org.apache.commons.codec.binary.Hex in JavaScript
      val bytes = "Hello, World".getBytes("UTF-8")
      bytes.hex shouldBe Hex.encodeHexString(bytes)
    }
  }
}