package com.evernym.verity.protocol.protocols.connections.v_1_0

import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.CommonProtoTypes.SigBlockCommunity
import com.evernym.verity.protocol.protocols.connections.v_1_0.Ctl.Init
import com.evernym.verity.util2.ServiceEndpoint


object ConnectionsMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.COMMUNITY_QUALIFIER
  override val name: MsgFamilyName = "connections"
  override val version: MsgFamilyVersion = "1.0"

  override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    "problem-report"                      -> classOf[Msg.ProblemReport],
    "request"                             -> classOf[Msg.ConnRequest],
    "response"                            -> classOf[Msg.ConnResponse],
    "ack"                                 -> classOf[Msg.Ack]
  )

  override protected val controlMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    "Init"                                -> classOf[Init],
    "accept"                              -> classOf[Ctl.Accept],
    "their-did-doc-updated"               -> classOf[Ctl.TheirDidDocUpdated],
    "their-did-updated"                   -> classOf[Ctl.TheirDidUpdated],
    "status"                              -> classOf[Ctl.Status]
  )

  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
    classOf[Signal.InvalidInvite]         -> "invalid-invite",
    classOf[Signal.InvitedWithDID]        -> "invited-with-did",
    classOf[Signal.InvitedWithKey]        -> "invited-with-key",

    classOf[Signal.SetupTheirDidDoc]      -> "setup-their-did-doc",
    classOf[Signal.UpdateTheirDid]        -> "update-their-did",
    classOf[Signal.ConnRequestSent]       -> "request-sent",
    classOf[Signal.ConnRequestReceived]   -> "request-received",
    classOf[Signal.ConnResponseSent]      -> "response-sent",
    classOf[Signal.ConnResponseReceived]  -> "response-received",
    classOf[Signal.Complete]              -> "complete",

    classOf[Signal.StatusReport]          -> "status-report",
    classOf[Signal.UnhandledError]        -> "unhandled-error"
  )
}

sealed trait Role

object Role {
  case object Inviter extends Role
  case object Invitee extends Role
}

// Messages
sealed trait Msg extends MsgBase

object Msg {
  // Question: Should non protocol message be here?
  // TODO: Ack and ProblemReport should be global
  case class ProblemReport(`problem-code`: String, explain: String) extends Msg
  case class Ack(status: Boolean) extends Msg

  // Question: Should i use com.evernym.verity.DidDoc
  object ProvisionalRelationship {
    def apply(rel: com.evernym.verity.protocol.protocols.connections.v_1_0.ProvisionalRelationship): ProvisionalRelationship = {
      Msg.ProvisionalRelationship(rel.did, rel.verKey, rel.endpoint, rel.theirVerKeys.toVector, rel.theirEndpoint, rel.theirRoutingKeys.toVector)
    }
  }

  case class ProvisionalRelationship(did: DidStr, verKey: VerKeyStr, endpoint: ServiceEndpoint,
                                     theirVerKeys: Vector[VerKeyStr], theirEndpoint: ServiceEndpoint,
                                     theirRoutingKeys: Vector[VerKeyStr]) extends Msg

  object Relationship {
    def apply(rel: com.evernym.verity.protocol.protocols.connections.v_1_0.Relationship): Relationship = {
      Msg.Relationship(rel.myDid, rel.myVerKey, rel.myEndpoint, rel.theirDid, rel.theirVerKey, rel.theirEndpoint, rel.theirRoutingKeys.toVector)
    }
  }
  case class Relationship(myDid: DidStr, myVerKey: VerKeyStr, myEndpoint: ServiceEndpoint,
                          theirDid: String, theirVerKey: VerKeyStr,
                          theirEndpoint: ServiceEndpoint, theirRoutingKeys: Vector[VerKeyStr]) extends Msg

  case class Connection(DID: DidStr, DIDDoc: DIDDocFormatted) extends Msg {
    def did: DidStr = DID
    def did_doc: DIDDocFormatted = DIDDoc
  }

  case class ConnRequest(label: String, connection: Connection) extends Msg
  case class ConnResponse(`connection~sig`: SigBlockCommunity) extends Msg

  case class InviteWithDID(did: DidStr, label: String) extends Msg

  case class InviteWithKey(serviceEndpoint: ServiceEndpoint,
                           recipientKeys: Vector[VerKeyStr],
                           routingKeys: Option[Vector[VerKeyStr]],
                           label: String) extends Msg {

    def routingKeys_! : Vector[VerKeyStr] = routingKeys.getOrElse(Vector.empty)
  }

  type Invitation = Either[InviteWithDID, InviteWithKey]
}

// Control Messages
sealed trait Ctl extends Control with MsgBase

object Ctl {

  case class Init(params: Parameters) extends Ctl

  case class Accept(label: String, invite_url: String) extends Ctl

  case class TheirDidDocUpdated(myDID: DidStr, myVerKey: VerKeyStr, myRoutingKeys: Vector[VerKeyStr]) extends Ctl

  case class TheirDidUpdated() extends Ctl

  case class Status() extends Ctl
}

// Signal Messages
sealed trait SignalMsg

object Signal {
  case class InvitedWithDID(invitation: Msg.InviteWithDID) extends SignalMsg
  case class InvitedWithKey(invitation: Msg.InviteWithKey) extends SignalMsg
  case class InvalidInvite(inviteURL: String) extends SignalMsg
  case class UnhandledError(message: String) extends SignalMsg

  case class SetupTheirDidDoc(myDID: DidStr, theirVerKey: VerKeyStr, theirServiceEndpoint: ServiceEndpoint,
                              theirRoutingKeys: Vector[VerKeyStr], theirDID: Option[DidStr]) extends SignalMsg

  case class UpdateTheirDid(myDID: DidStr, theirDID: DidStr) extends SignalMsg

  case class ConnRequestSent(req: Msg.ConnRequest) extends SignalMsg
  case class ConnRequestReceived(conn: Msg.Connection, myDID: DidStr) extends SignalMsg

  case class ConnResponseSent(resp: Msg.ConnResponse, myDID: DidStr) extends SignalMsg
  case class ConnResponseReceived(conn: Msg.Connection) extends SignalMsg

  case class ConnResponseInvalid() extends SignalMsg
  case class Complete(theirDid: DidStr) extends SignalMsg

  case class StatusReport(status: String) extends SignalMsg
}
