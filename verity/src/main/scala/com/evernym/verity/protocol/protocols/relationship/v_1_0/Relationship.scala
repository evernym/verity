package com.evernym.verity.protocol.protocols.relationship.v_1_0

import akka.http.scaladsl.model.Uri
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Ctl.{ConnectionInvitation, Create}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Msg.{Invitation, OutOfBandInvitation}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.ProblemReportCodes._
import com.evernym.verity.util.Base64Util
import org.json.JSONObject


class Relationship(val ctx: ProtocolContextApi[Relationship, Role, Msg, RelationshipEvent, State, String])
  extends Protocol[Relationship, Role, Msg, RelationshipEvent, State, String](RelationshipDef){

  def handleControl: Control ?=> Any = {
    case c: Control => mainHandleControl(ctx.getState, c)
  }

  // Control Message Handlers
  def mainHandleControl: (State, Control) ?=> Unit = {
    case (_: State.Uninitialized, m: Ctl.Init       ) =>
      ctx.apply(Initialized(m.params.initParams.map(p => InitParam(p.name, p.value)).toSeq))

    case (st: State.Initialized             , m: Create                     ) => handleCreateKey(st, m)
    case (st: State.KeyCreationInProgress   , m: Ctl.KeyCreated             ) => handleKeyCreated(st, m)
    case (st: State.Created                 , m: Ctl.ConnectionInvitation   ) => connectionInvitation(st, m)
    case (st: State.InvitationCreated       , m: Ctl.ConnectionInvitation   ) => prepareInvitation(st.invitation, m.shortInvite, "c_i")
    case (st: State.Created                 , m: Ctl.OutOfBandInvitation    ) => outOfBandInvitation(st, m)
    case (st: State.InvitationCreated       , m: Ctl.OutOfBandInvitation    ) => outOfBandInvitation(st, m)
    case (_: State.InvitationCreated        , m: Ctl.InviteShortened        ) =>
      ctx.signal(Signal.Invitation(m.longInviteUrl, Option(m.shortInviteUrl), getInvitationId(m.longInviteUrl)))
    case (_: State.InvitationCreated        , _: Ctl.InviteShorteningFailed ) =>
      ctx.signal(Signal.buildProblemReport("Shortening failed", shorteningFailed))
    case (st: State                         , m: Ctl                        ) => // unexpected state
      ctx.signal(Signal.buildProblemReport(
        s"Unexpected '${RelationshipMsgFamily.msgType(m.getClass).msgName}' message in current state '${st.getClass.getSimpleName}",
        unexpectedMessage
      ))

  }

  def handleCreateKey(st: State.Initialized, m: Ctl.Create): Unit = {
    ctx.apply(CreatingPairwiseKey(m.label.getOrElse(st.label), m.logoUrl.getOrElse(st.logoUrl)))
    ctx.signal(Signal.CreatePairwiseKey())
  }

  def handleKeyCreated(st: State.KeyCreationInProgress, m: Ctl.KeyCreated): Unit = {
    ctx.apply(PairwiseKeyCreated(st.label, m.did, m.verKey, st.profileUrl))
    ctx.signal(Signal.Created(m.did, m.verKey))
  }

  def outOfBandInvitation(st: State.Created, m: Ctl.OutOfBandInvitation): Unit = {
    val verKeys = Vector(st.verKey)
    val routingKeys = Option(Vector(st.verKey, st.agencyVerKey))
    val inviteCreatedEvent = InvitationCreated(st.label, ctx.serviceEndpoint, verKeys, routingKeys.getOrElse(Vector.empty))
    ctx.apply(inviteCreatedEvent)

    outOfBandInvitation(
      m,
      st.label,
      st.did,
      st.verKey,
      st.agencyVerKey,
      stringToOption(st.profileUrl),
      stringToOption(st.publicDid)
    )
  }

  def outOfBandInvitation(st: State.InvitationCreated, m: Ctl.OutOfBandInvitation): Unit = {
    outOfBandInvitation(
      m,
      st.label,
      st.did,
      st.verKey,
      st.agencyVerKey,
      st.invitation.profileUrl,
      stringToOption(st.publicDid)
    )
  }

  def outOfBandInvitation(m: Ctl.OutOfBandInvitation, label: String, did: DID,
                          verKey: VerKey, agencyVerKey: String, profileUrl: Option[String],
                          publicDid: Option[DID]): Unit = {
    val routingKeys = Option(Vector(verKey, agencyVerKey))
    val handshake_protocols = Vector("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/")

    val service = DIDDoc(did, verKey, ctx.serviceEndpoint, routingKeys.getOrElse(Vector.empty))
      .toDIDDocFormatted.service

    val invitationMsg = OutOfBandInvitation(label, m.goalCode, m.goal, handshake_protocols,
      m.`request~attach`, service, profileUrl, publicDid.map(d => "did:sov:" + d))
    prepareInvitation(invitationMsg, m.shortInvite, "oob")
  }

  def connectionInvitation(st: State.Created, m: ConnectionInvitation): Unit = {
    val verKeys = Vector(st.verKey)
    val routingKeys = Option(Vector(st.verKey, st.agencyVerKey))
    val inviteCreatedEvent = InvitationCreated(st.label, ctx.serviceEndpoint, verKeys, routingKeys.getOrElse(Vector.empty))
    ctx.apply(inviteCreatedEvent)

    val invitationMsg = Invitation(
      inviteCreatedEvent.label,
      inviteCreatedEvent.serviceEndpoint,
      inviteCreatedEvent.recipientKeys.toVector,
      Option(inviteCreatedEvent.routingKeys.toVector),
      stringToOption(st.profileUrl)
    )
    prepareInvitation(invitationMsg, m.shortInvite, "c_i")
  }

  def prepareInvitation(invitation: Msg.BaseInvitation, shortenUrl: Option[Boolean], queryName: String): Unit = {
    val inv = DefaultMsgCodec.toJson(invitation)
    val inviteURL = prepareInviteUrl(Base64Util.getBase64UrlEncoded(inv.getBytes), queryName)
    if (shortenUrl.getOrElse(false)) {
      ctx.signal(Signal.ShortenInvite(inviteURL))
    }
    else
      ctx.signal(Signal.Invitation(inviteURL, None, Option(invitation.`@id`)))
  }

  def prepareInviteUrl(invB64: String, queryName: String = "c_i"): String = {
    ctx.serviceEndpoint + s"?${queryName}=" + invB64
  }

  def stringToOption(str: String): Option[String] = {
    if (!str.isEmpty)
      Option(str)
    else
      None
  }

  def getInvitationId(inviteURL: String): Option[String] = {
    val inviteQuery = Uri(inviteURL).query()
    try {
      inviteQuery.get("c_i").orElse(inviteQuery.get("oob")) map { x =>
        val invJson = new JSONObject(new String(Base64Util.getBase64UrlDecoded(x)))
        invJson.getString("@id")
      }
    }
    catch {
      case e: Exception =>
        ctx.logger.warn("Getting invitation id failed", e)
        None
    }
  }

  override def applyEvent: ApplyEvent = {
    case (_: State.Uninitialized          , _ , e: Initialized          ) =>
      val paramMap = e.params map { p => InitParamBase(p.name, p.value) }
      val agencyVerKey = paramMap.find(_.name == AGENCY_DID_VER_KEY).map(_.value).getOrElse(throw new RuntimeException("agency did ver key not found"))
      val name = paramMap.find(_.name == NAME).map(_.value).getOrElse("")
      val logoUrl = paramMap.find(_.name == LOGO_URL).map(_.value).getOrElse("")
      val publicDid = paramMap.find(_.name == MY_PUBLIC_DID).map(_.value).getOrElse("")
      (State.Initialized(agencyVerKey, name, logoUrl, publicDid), initialize(paramMap))
    case (st: State.Initialized            , _ , cpk: CreatingPairwiseKey  ) =>
      val roster = ctx.getRoster
      (State.KeyCreationInProgress(cpk.label, st.agencyVerKey, cpk.profileUrl, st.publicDid),
        roster
          .withAssignment(Role.Provisioner -> roster.selfIndex_!)
          .withAssignment(Role.Requester   -> roster.otherIndex())
      )
    case (st: State.KeyCreationInProgress  , _ , e: PairwiseKeyCreated   ) =>
      State.Created(e.label, e.did, e.verKey, st.agencyVerKey, st.profileUrl, st.publicDid)

    case ( st: State.Created , _ , e: InvitationCreated ) =>
      State.InvitationCreated(Invitation(e.label, e.serviceEndpoint, e.recipientKeys.toVector,
        Option(e.routingKeys.toVector), stringToOption(st.profileUrl)),
        st.label, st.did, st.verKey, st.agencyVerKey, st.publicDid)
  }

  def initialize( paramMap: Seq[InitParamBase]): Roster[Role] = {
    ctx.updatedRoster(paramMap)
  }

  override def handleProtoMsg: (State, Option[Role], Msg) ?=> Any = ???

}

sealed trait Role
object Role {
  case object Provisioner extends Role
  case object Requester extends Role
}

trait RelationshipEvent

