package com.evernym.verity.protocol.protocols.relationship.v_1_0

import com.evernym.verity.ServiceEndpoint
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.didcomm.messages.ProblemDescription
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.urlShortening.InviteShortened
import com.evernym.verity.protocol.protocols.outofband.v_1_0.OutOfBandMsgFamily
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Ctl.Init
import com.evernym.verity.util.MsgIdProvider

object RelationshipMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.EVERNYM_QUALIFIER
  override val name: MsgFamilyName = "relationship"
  override val version: MsgFamilyVersion = "1.0"

  override protected val protocolMsgs: Map[MsgName, Class[_]] = Map.empty

  override protected val controlMsgs: Map[MsgName, Class[_]] = Map(
    "Init"                       -> classOf[Init],
    "create"                     -> classOf[Ctl.Create],
    "key-created"                -> classOf[Ctl.KeyCreated],
    "invite-shortened"           -> classOf[InviteShortened],
    "sms-sent"                   -> classOf[Ctl.SMSSent],
    "sms-sending-failed"         -> classOf[Ctl.SMSSendingFailed],
    "connection-invitation"      -> classOf[Ctl.ConnectionInvitation],
    "out-of-band-invitation"     -> classOf[Ctl.OutOfBandInvitation],
    "sms-connection-invitation"  -> classOf[Ctl.SMSConnectionInvitation],
    "sms-out-of-band-invitation" -> classOf[Ctl.SMSOutOfBandInvitation]
  )
  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
    classOf[Signal.CreatePairwiseKey]  -> "create-key",
    classOf[Signal.Created]            -> "created",
    classOf[Signal.Invitation]         -> "invitation",
    classOf[Signal.ProblemReport]      -> "problem-report",
    classOf[Signal.SendSMSInvite]      -> "send-sms-invite",
    classOf[Signal.SMSInvitationSent]  -> "sms-invitation-sent"
  )
}

sealed trait SignalMsg

case class Identity(DID: DID, verKey: VerKey)

object Signal {
  case class CreatePairwiseKey() extends SignalMsg
  case class Created(did: DID, verKey: VerKey) extends SignalMsg
  case class Invitation(inviteURL: String, shortInviteURL: Option[String], invitationId: String) extends SignalMsg
  case class SendSMSInvite(invitationId: String, inviteURL: String, senderName: String, phoneNo: String) extends SignalMsg
  case class SMSInvitationSent(invitationId: String)
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
                        `@type`: String = MsgFamily.typeStrFromMsgType(MsgFamily.COMMUNITY_QUALIFIER, "connections", "1.0", "invitation"),
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
                                 `@type`: String = MsgFamily.typeStrFromMsgType(MsgFamily.COMMUNITY_QUALIFIER, OutOfBandMsgFamily.name, OutOfBandMsgFamily.version, "invitation"), //"did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/" +
                                 `@id`: String = MsgIdProvider.getNewMsgId) extends BaseInvitation
}

sealed trait State
object State {
  case class Uninitialized() extends State
  case class Initialized(agencyVerKey: String, label: String, logoUrl: String, publicDid: DID) extends State
  case class KeyCreationInProgress(label: String, agencyVerKey: String, profileUrl: String, publicDid: DID, phoneNumber: Option[String]) extends State
  case class Created(label: String, did: DID, verKey: VerKey, agencyVerKey: String, profileUrl: String, publicDid: DID, phoneNumber: Option[String]) extends State
  case class InvitationCreated(invitation: Msg.Invitation, label: String, did: DID, verKey: VerKey, agencyVerKey: String, publicDid: DID, phoneNumber: Option[String]) extends State
}

sealed trait Ctl extends Control with MsgBase

object Ctl {
  case class Init(params: Parameters) extends Ctl
  case class Create(label: Option[String], logoUrl: Option[String], phoneNumber: Option[String]=None) extends Ctl
  case class KeyCreated(did: DID, verKey: VerKey) extends Ctl
  case class SMSSent(invitationId: String, longInviteUrl: String, shortInviteUrl: String) extends Ctl
  case class SMSSendingFailed(invitationId: String, reason: String) extends Ctl

  trait CreateInvitation extends Ctl
  case class ConnectionInvitation(shortInvite: Option[Boolean]=None) extends CreateInvitation
  case class OutOfBandInvitation(goalCode: String, goal: String, shortInvite: Option[Boolean]=None) extends CreateInvitation
  case class SMSConnectionInvitation() extends CreateInvitation
  case class SMSOutOfBandInvitation(goalCode: String, goal: String) extends CreateInvitation
}
