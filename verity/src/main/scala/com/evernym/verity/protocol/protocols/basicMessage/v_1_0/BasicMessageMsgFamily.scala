package com.evernym.verity.protocol.protocols.basicMessage.v_1_0

import com.evernym.verity.Base64Encoded
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.didcomm.decorators.{AppendingAttachment => Attachment}
import com.evernym.verity.protocol.protocols.CommonProtoTypes.{Timing => BaseTiming, Localization => l10n}

object BasicMessageMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = "BzCbsNYhMrjHiqZDTUASHg"
  override val name: MsgFamilyName = "basicmessage"
  override val version: MsgFamilyVersion = "1.0"

  override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    "message"      -> classOf[Msg.Message]
  )

  override protected val controlMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    "Init"              -> classOf[Ctl.Init],
    "send-message"      -> classOf[Ctl.SendMessage],
  )

  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
    classOf[Signal.ReceivedMessage] -> "received-message",
  )
}

// Sub Types

// Messages
sealed trait Msg extends MsgBase

object Msg {

  case class Message(`~l10n`: l10n = l10n(locale = Some("en")),
                      sent_time: BaseTiming,
                      content: String = "",
                     `~attach`: Option[Vector[Attachment]] = None,
                     ) extends Msg
}

// Control Messages
sealed trait Ctl extends Control with MsgBase

object Ctl {

  case class Init(selfId: ParameterValue, otherId: ParameterValue) extends Ctl

  case class SendMessage(`~l10n`: l10n = l10n(locale = Some("en")),
                         sent_time: BaseTiming,
                         content: String = "",
                         `~attach`: Option[Vector[Attachment]] = None,
                        ) extends Ctl
}

// Signal Messages
sealed trait SignalMsg

object Signal {
  case class ReceivedMessage(`~l10n`: l10n,
                            sent_time: BaseTiming,
                            content: String,
                             `~attach`: Option[Vector[Attachment]]
                            ) extends SignalMsg
}
