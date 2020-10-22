package com.evernym.verity.agentmsg.msgcodec

import com.evernym.verity.actor.agent.TypeFormat

import scala.util.Try

class UnknownFormatType(message: String) extends Exception(message)

trait TypeFormatLike {
  def toString: String
}

trait NoopTypeFormat extends TypeFormatLike // Do NOT inject a message type
{
  override val toString = "noop"
}
trait LegacyTypeFormat extends TypeFormatLike //"{"@type":{"name":"connect","ver":"1.0"}}"
{
  override val toString = "0.5"
}
trait StandardTypeFormat extends TypeFormatLike //"{"@type":"did:sov:<DID>;spec/TicTacToe/0.5/OFFER"}
{
  override val toString = "1.0"
}

trait TypeFormatCompanion {
  def fromString(v: String): TypeFormat = {
    v.trim match {
      case "noop" => TypeFormat.NOOP_TYPE_FORMAT
      case "0.5" => TypeFormat.LEGACY_TYPE_FORMAT
      case "1.0" => TypeFormat.STANDARD_TYPE_FORMAT
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