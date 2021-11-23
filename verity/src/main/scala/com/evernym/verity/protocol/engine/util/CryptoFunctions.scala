package com.evernym.verity.protocol.engine.util

import java.security.MessageDigest
import com.evernym.verity.protocol.engine.{ContextId, ThreadId}
import org.apache.commons.codec.digest.DigestUtils

import scala.language.implicitConversions

trait CryptoFunctions {
  final def sha256(input: String): Array[Byte] = {
    sha256(input.getBytes("UTF-8"))
  }
  def sha256(input: Array[Byte]): Array[Byte]

  def computeSafeThreadId(contextId: ContextId, threadId: ThreadId): ThreadId = {
    safeHashTrunc16(contextId, threadId)
  }

  def safeHashTrunc16(strs: String*): String = {
    safeHashBytes(strs:_*).truncate(16).hex
  }

  /** Hashes each string and returns the hash of the hashes: H( H(x) | H(y) ).
    *
    * This is safer than concatenating the strings and hashing the result: H(x|y).
    * For example, H("abc"|"def") would equal H("abcd"|"ef"), which means a
    * malicious user could potentially supply a string that could result in a
    * matching hash when they shouldn't be able to. This effectively sanitizes
    * user input. This function is safe because H(H("abc")|H("def")) would not
    * equal H(H("abcd")|H("ef")).
    *
    * @param strs a variable number of strings
    * @return H( H(str(1)) | H(str(2)) | ... | H(str(n)) )
    */

  def safeHashBytes(strs: String*): Array[Byte] = {
    sha256(strs.map(sha256).foldLeft(Array.empty[Byte]) { (accumulator, current) => accumulator ++ current})
  }

  def safeHash(strs: String*): String = safeHashBytes(strs:_*).hex

  implicit def byteArray2RichBytes(bytes: Array[Byte]): RichBytes = new RichBytes(bytes)
}

object CryptoFunctions extends CryptoFunctions {
  override def sha256(input: Array[Byte]): Array[Byte] = {
    val md: MessageDigest = DigestUtils.getSha256Digest
    md.update(input)
    md.digest
  }
}

object RichBytes {
  def encodeHexString(bytes: Array[Byte]): String = bytes.map("%02X"format _).mkString.toLowerCase
}

class RichBytes(val bytes: Array[Byte]) {
  def truncate(length: Int): Array[Byte] = bytes.take(length)
  def hex: String = RichBytes.encodeHexString(bytes)
}