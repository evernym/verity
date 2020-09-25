package com.evernym.verity.util

import java.security.MessageDigest

import org.apache.commons.codec.binary.{Hex, StringUtils}
import org.apache.commons.codec.digest.{DigestUtils, MessageDigestAlgorithms}

import scala.collection.GenTraversable
import scala.language.implicitConversions


class RichBytes(val bytes: Array[Byte]) {
  def truncate(length: Int): Array[Byte] = bytes.take(length)
  def hex: String = Hex.encodeHexString(bytes)
}

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

  object SHA256_trunc8 extends HashAlgorithm {
    val libName: String = MessageDigestAlgorithms.SHA_256
    override val truncateBytes = Some(8)
  }

  object SHA256_trunc4 extends HashAlgorithm {
    val libName: String = MessageDigestAlgorithms.SHA_256
    override val truncateBytes = Some(4)
  }

  object SHA512 extends HashAlgorithm {
    val libName: String = MessageDigestAlgorithms.SHA_512
  }

}

object HashUtil {
  implicit def byteArray2RichBytes(bytes: Array[Byte]): RichBytes = new RichBytes(bytes)

  private def truncateOpt(bytes: Array[Byte], len: Option[Int]): Array[Byte] = {
    len match {
      case Some(n) => bytes.take(n)
      case None => bytes
    }
  }

  private def strDigest(algo: HashAlgorithm)(str: String) = {
    Option(str)
      .orElse(throw new NullPointerException("Null string can't be used with HashUtil"))
      .map(StringUtils.getBytesUtf8)
      .map(DigestUtils.getDigest(algo.libName).digest)
      .getOrElse(throw new RuntimeException(s"Unable to digest String - $str"))
  }

  /**
    * Hashes a single String with out double hashing the single string.
    *
    * This function should not be used to hash a concatenation of multiple
    * strings. See safeMultiHash for those use-cases.
    * @param algo hashing algorithm used
    * @param str single string to be hashed
    * @return Byte Array of the calculated hash
    */
  def hash(algo: HashAlgorithm)(str: String): Array[Byte] = truncateOpt(strDigest(algo)(str), algo.truncateBytes)

  /** Hashes each string and returns the hash of the hashes: H( H(x) | H(y) ).
    *
    * This is safer than concatenating the strings and hashing the result: H(x|y).
    * For example, H("abc"|"def") would equal H("abcd"|"ef"), which means a
    * malicious user could potentially supply a string that could result in a
    * matching hash when they shouldn't be able to. This effectively sanitizes
    * user input. This function is safe because H(H("abc")|H("def")) would not
    * equal H(H("abcd")|H("ef")).
    *
    * @param algo hashing algorithm used
    * @param strs a variable number of strings
    * @return Byte Array of the calculated hash
    */
  def safeMultiHash(algo: HashAlgorithm, strs: String*): Array[Byte] = safeIterMultiHash(algo, strs.toSeq)

  def safeIterMultiHash(algo: HashAlgorithm, strs: GenTraversable[String]): Array[Byte] = {
    val md: MessageDigest = DigestUtils.getDigest(algo.libName)

    strs
      .map(strDigest(algo: HashAlgorithm)) // convert each string to individual hash
      .foreach(md.update) // combine all individual hash to single hash

    truncateOpt(md.digest, algo.truncateBytes)
  }


}
