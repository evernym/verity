package com.evernym.verity.agentmsg.msgcodec

import scala.util.Try

class UnknownFormatType(message: String) extends Exception(message)

sealed trait TypeFormat {
  def toString: String
}

case object NoopTypeFormat     extends TypeFormat // Do NOT inject a message type
{
  override val toString = "noop"
}
case object LegacyTypeFormat   extends TypeFormat //"{"@type":{"name":"connect","ver":"1.0"}}"
{
  override val toString = "0.5"
}
case object StandardTypeFormat extends TypeFormat //"{"@type":"did:sov:<DID>;spec/TicTacToe/0.5/OFFER"}
{
  override val toString = "1.0"
}

object TypeFormat {
  def fromString(v: String): TypeFormat = {
    v.trim match {
      case "noop" => NoopTypeFormat
      case "0.5" => LegacyTypeFormat
      case "1.0" => StandardTypeFormat
      case t => throw new UnknownFormatType("invalid msg type format version found: " + t)
    }
  }

  def knownTypeFormat(any: Any): Boolean = {
    any match {
      case _: TypeFormat => true
      case s: String =>
        Try(fromString(s))
          .map(knownTypeFormat)
          .getOrElse(false)
      case _ => false
    }
  }
}