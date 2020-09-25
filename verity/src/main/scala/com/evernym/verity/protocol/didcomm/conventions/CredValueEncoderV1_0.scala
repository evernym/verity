package com.evernym.verity.protocol.didcomm.conventions

import java.nio.ByteBuffer

import com.evernym.verity.util.{HashAlgorithm, HashUtil}

import scala.util.{Failure, Success, Try}

object CredValueEncoderV1_0 {

  val ZERO: Byte = 0

  def encodedValue(arg: Any): String = {
    Option(arg) match {
      case Some(s: String) =>
        if (is32BitInt(s)) s
        else encodeString(s)
      case Some(c: Char) => encodeString(c.toString)
      case Some(b: Boolean) =>
        if (b) "1"
        else "0"
      case Some(i: Int) => i.toString
      case Some(i: Long) => encodeString(i.toString)
      case Some(f: Float) => encodeString(f.toString)
      case Some(f: Double) => encodeString(f.toString)
      case Some(_) => throw new Exception("Not encoded type")
      case None => encodeString("None")
    }
  }

  def encodeString(arg: String): String = {
    val sha256Digest = HashUtil.hash(HashAlgorithm.SHA256)(arg)
    val bigIntStr = convertToBigIntString(sha256Digest)
    bigIntStr
  }

  def convertToBigIntString(bytes: Array[Byte]): String = {
    BigInt(
      ByteBuffer
        .allocate(33)
        .put(ZERO) // Zero pad the array to force BigInt to see this number as positive (emulate unsigned BigInteger)
        .put(bytes)
        .array()
    ).toString()
  }

  def is32BitInt(arg: Any): Boolean = {
    Try(arg.toString.toInt) match {
      case Success(_)     => true
      case Failure(_) => false
    }
  }
}
