package com.evernym.verity.protocol.protocols.outofband.v_1_0

import com.evernym.verity.actor.agent.Thread
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.didcomm.messages.{AdoptableProblemReport, ProblemDescription}
import com.evernym.verity.protocol.engine._

object OutOfBandMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.COMMUNITY_QUALIFIER
  override val name: MsgFamilyName = "out-of-band"
  override val version: MsgFamilyVersion = "1.0"

  override protected val protocolMsgs: Map[MsgName, Class[_]] = Map(
    "handshake-reuse"                   -> classOf[Msg.HandshakeReuse],
    "handshake-reuse-accepted"          -> classOf[Msg.HandshakeReuseAccepted],
    "problem-report"                    -> classOf[Msg.ProblemReport]
  )

  override protected val controlMsgs: Map[MsgName, Class[_]] = Map(
    "Init"                   -> classOf[Ctl.Init],
    "reuse"                  -> classOf[Ctl.Reuse]
  )
  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
    classOf[Signal.ConnectionReused]  -> "relationship-reused",
    classOf[Signal.ProblemReport]     -> "problem-report"
  )
}

sealed trait SignalMsg

case class Identity(DID: DID, verKey: VerKey)

object Signal {
  case class ConnectionReused(`~thread`: Thread, relationship: DID) extends SignalMsg
  case class ProblemReport(description: ProblemDescription) extends AdoptableProblemReport with SignalMsg
  def buildProblemReport(description: String, code: String): Signal.ProblemReport = {
    Signal.ProblemReport(
      ProblemDescription(
        Some(description),
        code
      )
    )
  }
}

sealed trait Msg extends MsgBase
object Msg {

  case class HandshakeReuse(`~thread`: Thread) extends Msg
  case class HandshakeReuseAccepted(`~thread`: Thread) extends Msg
  case class ProblemReport(description: ProblemDescription) extends AdoptableProblemReport with Msg
  def buildProblemReport(description: String, code: String): Msg.ProblemReport = {
    Msg.ProblemReport(
      ProblemDescription(
        Some(description),
        code
      )
    )
  }
}

sealed trait State
object State {
  case class Uninitialized() extends State
  case class Initialized() extends State
  case class ConnectionReuseRequested() extends State
  case class ConnectionReused() extends State
}

sealed trait Ctl extends Control with MsgBase

object Ctl {
  case class Init(params: Parameters) extends Ctl
  case class Reuse(inviteUrl: String) extends Ctl with Msg
}