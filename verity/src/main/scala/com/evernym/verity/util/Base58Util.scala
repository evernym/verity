package com.evernym.verity.util

import scala.util.Try

/**
  * A custom form of base58 is used to encode Scorex addresses.
  * Following code is from scorex library ( "org.scorexfoundation" % "scrypto_2.12" % "1.2.1")
  * Code available online at https://github.com/input-output-hk/scrypto/blob/master/src/main/scala/scorex/crypto/encode/Base58.scala
  *
  * Scorex library is heavy as it lot of functionality. We only needed the base58 encoding from that library.
  * Hence the library is removed and only relevant code is used from library
  */


object Base58Util {
  private val Alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
  private val Base = BigInt(58)

  def encode(input: Array[Byte]): String = {
    var bi = BigInt(1, input)
    val s = new StringBuilder()
    if (bi > 0) {
      while (bi >= Base) {
        val mod = bi.mod(Base)
        s.insert(0, Alphabet.charAt(mod.intValue))
        bi = (bi - mod) / Base
      }
      s.insert(0, Alphabet.charAt(bi.intValue))
    }
    // Convert leading zeros too.
    input.takeWhile(_ == 0).foldLeft(s) { case (ss, _) =>
      ss.insert(0, Alphabet.charAt(0))
    }.toString()
  }

  def decode(input: String): Try[Array[Byte]] = Try {
    require(input.nonEmpty, "Empty input for Base58.decode")

    val decoded = decodeToBigInteger(input)

    val bytes: Array[Byte] = if (decoded == BigInt(0)) Array.empty else decoded.toByteArray
    // We may have got one more byte than we wanted, if the high bit of the next-to-last byte was not zero.
    // This  is because BigIntegers are represented with twos-compliment notation,
    // thus if the high bit of the last  byte happens to be 1 another 8 zero bits will be added to
    // ensure the number parses as positive. Detect that case here and chop it off.
    val stripSignByte = bytes.length > 1 && bytes.head == 0 && bytes(1) < 0
    val stripSignBytePos = if (stripSignByte) 1 else 0
    // Count the leading zeros, if any.
    val leadingZeros = input.takeWhile(_ == Alphabet.charAt(0)).length

    // Now cut/pad correctly. Java 6 has a convenience for this, but Android
    // can't use it.
    val tmp = new Array[Byte](bytes.length - stripSignBytePos + leadingZeros)
    System.arraycopy(bytes, stripSignBytePos, tmp, leadingZeros, tmp.length - leadingZeros)
    tmp
  }

  private def decodeToBigInteger(input: String): BigInt =
  // Work backwards through the string.
    input.foldRight((BigInt(0), input.length - 1)) { case (ch, (bi, i)) =>
      val alphaIndex = Alphabet.indexOf(ch)
        .ensuring(_ != -1, "Wrong char in Base58 string")
      (bi + BigInt(alphaIndex) * Base.pow(input.length - 1 - i), i - 1)
    }._1
}