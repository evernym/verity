package com.evernym.verity.protocol.protocols.outofband.v_1_0

import com.evernym.verity.actor.agent.Thread
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.didcomm.decorators.AttachmentDescriptor
import com.evernym.verity.protocol.didcomm.messages.{AdoptableProblemReport, ProblemDescription}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.util.{Base64Util, MsgIdProvider}

object OutOfBandMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.COMMUNITY_QUALIFIER
  override val name: MsgFamilyName = "out-of-band"
  override val version: MsgFamilyVersion = "1.0"

  override protected val protocolMsgs: Map[MsgName, Class[_]] = Map(
    "handshake-reuse"                   -> classOf[Msg.HandshakeReuse],
    "handshake-reuse-accepted"          -> classOf[Msg.HandshakeReuseAccepted],
    "problem-report"                    -> classOf[Msg.ProblemReport],
    "invitation"                        -> classOf[Msg.OutOfBandInvitation]
  )

  override protected val controlMsgs: Map[MsgName, Class[_]] = Map(
    "Init"                   -> classOf[Ctl.Init],
    "reuse"                  -> classOf[Ctl.Reuse]
  )
  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
    classOf[Signal.ConnectionReused]  -> "relationship-reused",
    classOf[Signal.MoveProtocol]      -> "move-protocol",
    classOf[Signal.ProblemReport]     -> "problem-report"
  )
}

sealed trait SignalMsg

case class Identity(DID: DID, verKey: VerKey)

object Signal {
  case class ConnectionReused(`~thread`: Thread, relationship: DID) extends SignalMsg
  case class MoveProtocol(protoRefStr: String, fromRelationship: DID, toRelationship: DID, threadId: ThreadId)
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

  case class OutOfBandInvitation(label: String,
                                 goal_code: String,
                                 goal: String,
                                 `request~attach`: Vector[AttachmentDescriptor],
                                 service: Vector[ServiceFormatted],
                                 profileUrl: Option[String],
                                 public_did: Option[String],
                                 `@id`: String = MsgIdProvider.getNewMsgId
                                ) extends Msg {
    val `@type`: String = MsgFamily.typeStrFromMsgType(OutOfBandMsgFamily.msgType(getClass))

    // TODO - this should be dynamic (configurable?) but for now it is hardcoded
    val handshake_protocols: Vector[String] = Vector("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/")
  }

  def prepareInviteUrl(invitation: OutOfBandInvitation, urlEndpoint: String): String = {
    val inv = DefaultMsgCodec.toJson(invitation)
    urlEndpoint + "?oob=" + Base64Util.getBase64UrlEncoded(inv.getBytes) // FIXME use a lib to build a correct URL
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