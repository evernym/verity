package com.evernym.verity.protocol.protocols.relationship.v_1_0

import com.evernym.verity.ServiceEndpoint
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.didcomm.messages.ProblemDescription
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.outofband.v_1_0.OutOfBandMsgFamily
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Ctl.Init
import com.evernym.verity.util.MsgIdProvider

object RelationshipMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.EVERNYM_QUALIFIER
  override val name: MsgFamilyName = "relationship"
  override val version: MsgFamilyVersion = "1.0"

  override protected val protocolMsgs: Map[MsgName, Class[_]] = Map.empty

  override protected val controlMsgs: Map[MsgName, Class[_]] = Map(
    "Init"                    -> classOf[Init],
    "create"                  -> classOf[Ctl.Create],
    "key-created"             -> classOf[Ctl.KeyCreated],
    "invite-shortened"        -> classOf[Ctl.InviteShortened],
    "invite-shortening-failed"-> classOf[Ctl.InviteShorteningFailed],
    "connection-invitation"   -> classOf[Ctl.ConnectionInvitation],
    "out-of-band-invitation"  -> classOf[Ctl.OutOfBandInvitation]
  )
  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
    classOf[Signal.CreatePairwiseKey]  -> "create-key",
    classOf[Signal.Created]            -> "created",
    classOf[Signal.Invitation]         -> "invitation",
    classOf[Signal.ProblemReport]      -> "problem-report",
    classOf[Signal.ShortenInvite]      -> "shorten-invite"
  )
}

sealed trait SignalMsg

case class Identity(DID: DID, verKey: VerKey)

object Signal {
  case class CreatePairwiseKey() extends SignalMsg
  case class Created(did: DID, verKey: VerKey) extends SignalMsg
  case class Invitation(inviteURL: String, shortInviteURL: Option[String], invitationId: Option[String]) extends SignalMsg
  case class ShortenInvite(inviteURL: String) extends SignalMsg
  case class ProblemReport(description: ProblemDescription) extends SignalMsg
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
  trait BaseInvitation extends Msg {
    def `@id`: String
  }

  case class Invitation(label: String,
                        serviceEndpoint: ServiceEndpoint,
                        recipientKeys: Vector[VerKey],
                        routingKeys: Option[Vector[VerKey]],
                        profileUrl: Option[String],
                        `@type`: String = "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/invitation",
                        `@id`: String = MsgIdProvider.getNewMsgId) extends BaseInvitation {

    def routingKeys_! : Vector[VerKey] = routingKeys.getOrElse(Vector.empty)
  }

  case class OutOfBandInvitation(label: String,
                                 goal_code: String,
                                 goal: String,
                                 handshake_protocols: Vector[String],
                                 `request~attach`: Vector[String],
                                 service: Vector[ServiceFormatted],
                                 profileUrl: Option[String],
                                 public_did: Option[String],
                                 `@type`: String = "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/" +
                                   OutOfBandMsgFamily.name + "/1.0/invitation",
                                 `@id`: String = MsgIdProvider.getNewMsgId) extends BaseInvitation
}

sealed trait State
object State {
  case class Uninitialized() extends State
  case class Initialized(agencyVerKey: String, label: String, logoUrl: String, publicDid: DID) extends State
  case class KeyCreationInProgress(label: String, agencyVerKey: String, profileUrl: String, publicDid: DID) extends State
  case class Created(label: String, did: DID, verKey: VerKey, agencyVerKey: String, profileUrl: String, publicDid: DID) extends State
  case class InvitationCreated(invitation: Msg.Invitation, label: String, did: DID, verKey: VerKey, agencyVerKey: String, publicDid: DID) extends State
}

sealed trait Ctl extends Control with MsgBase

object Ctl {
  case class Init(params: Parameters) extends Ctl
  case class Create(label: Option[String], logoUrl: Option[String]) extends Ctl
  case class KeyCreated(did: DID, verKey: VerKey) extends Ctl
  case class InviteShortened(longInviteUrl: String, shortInviteUrl: String) extends Ctl
  case class InviteShorteningFailed(reason: String) extends Ctl

  trait CreateInvitation extends Ctl
  case class ConnectionInvitation(shortInvite: Option[Boolean]=None) extends CreateInvitation
  case class OutOfBandInvitation(goalCode: String, goal: String, `request~attach`: Vector[String], shortInvite: Option[Boolean]=None) extends CreateInvitation
}
