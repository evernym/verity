package com.evernym.verity.util

import java.util.Base64

object Base64Util {
  def getBase64Encoded(bytes: Array[Byte]): String = Base64.getEncoder.encodeToString(bytes)
  def getBase64UrlEncoded(bytes: Array[Byte]): String = Base64.getUrlEncoder.encodeToString(bytes)

  // Question: No error handling on decoding?
  def getBase64Decoded(str: String): Array[Byte] = Base64.getDecoder.decode(str)
  def getBase64UrlDecoded(str: String): Array[Byte] = Base64.getUrlDecoder.decode(str)
  def getBase64MultiDecoded(str: String): Array[Byte] = Base64.getDecoder.decode(str.replace('_', '/').replace('-', '+'))

  def urlDecodeToStr(str: String): String = new String(getBase64UrlDecoded(str))

  def getBase64String(seedOpt: Option[String] = None, length: Int): String = {
    val seed = Util.getSeed(seedOpt)
    getBase64Encoded(seed.getBytes).take(length)
  }
}
