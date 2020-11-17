package com.evernym.verity.actor.agent

import com.evernym.verity.actor.agent.MsgPackFormat._

trait MsgPackFormatLike {
  def toString: String

  def isEqual(mpf: String): Boolean = this == MsgPackFormat.fromString(mpf)
}

// used by REST
trait Plain {
  override val toString = "plain"
}

// message pack
trait MsgPack {
  override val toString = "0.5"
}

// indy pack
trait IndyPack {
  override val toString = "1.0"
}

trait MsgPackFormatCompanion {
  def fromString(v: String): MsgPackFormat = {
    v.trim match {
      case MPF_PLAIN.toString     => MPF_PLAIN
      case MPF_MSG_PACK.toString  => MPF_MSG_PACK
      case MPF_INDY_PACK.toString => MPF_INDY_PACK
      case v => throw new UnknownMsgPackVersion("invalid msg pack format found: " + v)
    }
  }
}

class UnknownMsgPackVersion(message: String) extends Exception(message)
