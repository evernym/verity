package com.evernym.verity.actor.agent

import com.evernym.verity.actor.agent.MsgPackVersion._

trait MsgPackVersionLike {
  def toString: String

  def isEqual(mpv: String): Boolean = this == MsgPackVersion.fromString(mpv)
}

// used by REST
trait Plain {
  override val toString = "plain"
}//case object MPV_PLAIN extends MsgPackVersionLike with Plain

// message pack
trait MsgPack {
  override val toString = "0.5"
}
//case object MPV_MSG_PACK extends MsgPackVersionLike with MsgPack


// indy pack
trait IndyPack {
  override val toString = "1.0"
}
//case object MPV_INDY_PACK extends MsgPackVersionLike with IndyPack

trait MsgPackVersionCompanion {
  def fromString(v: String): MsgPackVersion = {
    v.trim match {
      case MPV_PLAIN.toString     => MPV_PLAIN
      case MPV_MSG_PACK.toString  => MPV_MSG_PACK
      case MPV_INDY_PACK.toString => MPV_INDY_PACK
      case v => throw new UnknownMsgPackVersion("invalid msg pack version found: " + v)
    }
  }
}

//object MsgPackVersion extends MsgPackVersionCompanion

class UnknownMsgPackVersion(message: String) extends Exception(message)
