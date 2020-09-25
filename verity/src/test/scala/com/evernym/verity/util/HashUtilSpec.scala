package com.evernym.verity.util

import com.evernym.verity.util.HashAlgorithm._
import com.evernym.verity.util.HashUtil._
import org.apache.commons.codec.digest.DigestUtils
import com.evernym.verity.testkit.BasicSpec


class HashUtilSpec extends BasicSpec {
  "Hashing a single string" - {
    "truncated 4 sha256 should work" in {
      HashUtil.hash(SHA256_trunc4)("https://e01.example.com/invite?t=4k5j5j4").hex shouldBe "cba70dbd"
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

      val base = safeMultiHash(SHA256_trunc16, "abc", "def", "ghi")

      base should be (truncatedHash)
    }

    "should result in the same result every time" in {
      val base = safeMultiHash(SHA256_trunc16, "abc", "def", "ghi")
      val base2 = safeMultiHash(SHA256_trunc16, "abc", "def", "ghi")
      base shouldBe base2
    }

    "should NOT be the same as the hash of the same items in a different order" in {
      val base = safeMultiHash(SHA256_trunc16, "abc", "def", "ghi")
      val reordered = safeMultiHash(SHA256_trunc16, "def", "abc", "ghi")
      base should not be reordered
    }

    "should NOT be the same as the hash of the concatenated items" in {
      val base = safeMultiHash(SHA256_trunc16, "abc", "def", "ghi")
      val concat = safeMultiHash(SHA256_trunc16, "abc" + "def" + "ghi")
      base should not be concat
    }

    "should NOT be the same as the hash of the items with elements shifted" in {
      val base = safeMultiHash(SHA256_trunc16, "abc", "def", "ghi")
      val shifted = safeMultiHash(SHA256_trunc16, "abcd", "ef", "ghi")
      base should not be shifted
    }

    "Seq version works same as Array" in {
      val hash1 = safeMultiHash(SHA256, "test", "one")
      val hash2 = safeIterMultiHash(SHA256, Seq("test", "one"))

      hash1 shouldBe hash2
    }
  }
}
