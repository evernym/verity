package com.evernym.verity.protocol.protocols.basicMessage.v_1_0

import com.evernym.verity.Base64Encoded
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.didcomm.messages.ProblemDescription
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.CommonProtoTypes.{Timing => BaseTiming}

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
    "get-status"        -> classOf[Ctl.GetStatus],
  )

  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
    classOf[Signal.ReceiveMessage]   -> "receive-message",
    classOf[Signal.ProblemReport] -> "problem-report",
    classOf[Signal.StatusReport]  -> "status-report",
  )
}

// Sub Types
case class MessageResponse(text: String, nonce: Nonce)

// Messages
sealed trait Msg extends MsgBase

object Msg {

  case class Message(`~l10n`: String,
                      sent_time: BaseTiming,
                      content: String
                     ) extends Msg
}

case class Sig(signature: Base64Encoded,
               sig_data: Base64Encoded,
               timestamp: String)

// Control Messages
sealed trait Ctl extends Control with MsgBase

object Ctl {

  case class Init(selfId: ParameterValue, otherId: ParameterValue) extends Ctl

  case class SendMessage(`~l10n`: String,
                         sent_time: BaseTiming,
                         content: String) extends Ctl

  case class GetStatus() extends Ctl
}

// Signal Messages
sealed trait SignalMsg

object Signal {
  case class ReceiveMessage(`~l10n`: String,
                            sent_time: BaseTiming,
                            content: String) extends SignalMsg

  case class StatusReport(status: String) extends SignalMsg

  case class ProblemReport(description: ProblemDescription) extends SignalMsg

  def buildProblemReport(description: String, code: String): ProblemReport = {
    Signal.ProblemReport(
      ProblemDescription(
        Some(description),
        code
      )
    )
  }
}
