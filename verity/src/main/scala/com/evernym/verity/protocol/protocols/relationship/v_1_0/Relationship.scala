package com.evernym.verity.protocol.protocols.relationship.v_1_0

import akka.http.scaladsl.model.Uri
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.urlShortening.InviteShortened
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Ctl.Create
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Msg.{Invitation, OutOfBandInvitation}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.ProblemReportCodes._
import com.evernym.verity.util.Base64Util
import com.evernym.verity.util.OptionUtil.blankOption
import com.evernym.verity.util.Util.isPhoneNumberInValidFormat
import org.json.JSONObject

import scala.util.{Failure, Success, Try}


class Relationship(val ctx: ProtocolContextApi[Relationship, Role, Msg, RelationshipEvent, State, String])
  extends Protocol[Relationship, Role, Msg, RelationshipEvent, State, String](RelationshipDef){

  val defaultShortInviteOption = false

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
    case (st: State.InvitationCreated       , m: Ctl.ConnectionInvitation   ) => connectionInvitation(st, m)
    case (st: State.Created                 , m: Ctl.SMSConnectionInvitation) => connectionInvitation(st, m)
    case (st: State.InvitationCreated       , m: Ctl.SMSConnectionInvitation) => connectionInvitation(st, m)
    case (st: State.Created                 , m: Ctl.OutOfBandInvitation    ) => outOfBandInvitation(st, m)
    case (st: State.InvitationCreated       , m: Ctl.OutOfBandInvitation    ) => outOfBandInvitation(st, m)
    case (st: State.Created                 , m: Ctl.SMSOutOfBandInvitation ) => outOfBandInvitation(st, m)
    case (st: State.InvitationCreated       , m: Ctl.SMSOutOfBandInvitation ) => outOfBandInvitation(st, m)
    case (_: State.InvitationCreated        , m: Ctl.SMSSent                ) =>
      ctx.signal(Signal.SMSInvitationSent(m.invitationId))
    case (_: State.InvitationCreated        , _: Ctl.SMSSendingFailed       ) =>
      ctx.signal(Signal.buildProblemReport("SMS sending failed", smsSendingFailed))
    case (st: State                         , m: Ctl                        ) => // unexpected state
      ctx.signal(Signal.buildProblemReport(
        s"Unexpected '${RelationshipMsgFamily.msgType(m.getClass).msgName}' message in current state '${st.getClass.getSimpleName}",
        unexpectedMessage
      ))

  }

  def handleCreateKey(st: State.Initialized, m: Ctl.Create): Unit = {
    if (checkPhoneNumberValidOrNotProvided(m.phoneNumber)) {
      ctx.apply(CreatingPairwiseKey(m.label.getOrElse(st.label), m.logoUrl.getOrElse(st.logoUrl), m.phoneNumber.getOrElse("")))
      ctx.signal(Signal.CreatePairwiseKey())
    } else {
      ctx.signal(Signal.buildProblemReport(
        "Phone number provided is not in valid international format.",
        invalidPhoneNumberFormat
      ))
    }
  }

  def checkPhoneNumberValidOrNotProvided(phoneNumber: Option[String]): Boolean = {
    // if phone number is provided, it should have valid format.
    phoneNumber match {
      case Some(phoneNumber) => isPhoneNumberInValidFormat(phoneNumber)
      case None => true
    }
  }

  def handleKeyCreated(st: State.KeyCreationInProgress, m: Ctl.KeyCreated): Unit = {
    ctx.apply(PairwiseKeyCreated(st.label, m.did, m.verKey, st.profileUrl))
    ctx.signal(Signal.Created(m.did, m.verKey))
  }

  def connectionInvitation(st: State.Created, m: Ctl.ConnectionInvitation): Unit = {
    createInviteCreatedEvent(st)
    val invitationMsg = prepareConnectionInvitation(st)
    val inviteURL = prepareInviteUrl(invitationMsg)

    if (m.shortInvite.getOrElse(defaultShortInviteOption))
      ctx.urlShortening.shorten(inviteURL)(shortenerHandler(invitationMsg.`@id`))
     else
      ctx.signal(Signal.Invitation(inviteURL, None, invitationMsg.`@id`))
  }

  def connectionInvitation(st: State.InvitationCreated, m: Ctl.ConnectionInvitation): Unit = {
    val inviteURL = prepareInviteUrl(st.invitation)
    if (m.shortInvite.getOrElse(defaultShortInviteOption))
      ctx.urlShortening.shorten(inviteURL)(shortenerHandler(st.invitation.`@id`))
    else
      ctx.signal(Signal.Invitation(inviteURL, None, st.invitation.`@id`))
  }

  def connectionInvitation(st: State.Created, m: Ctl.SMSConnectionInvitation): Unit = {
    st.phoneNumber match {
      case Some(phoneNo) =>
        createInviteCreatedEvent(st)
        val invitationMsg = prepareConnectionInvitation(st)
        val inviteURL = prepareInviteUrl(invitationMsg)
        ctx.signal(Signal.SendSMSInvite(invitationMsg.`@id`, inviteURL, st.label, phoneNo))
      case None =>
        ctx.signal(smsNoPhoneNumberSignal)
    }
  }

  def connectionInvitation(st: State.InvitationCreated, m: Ctl.SMSConnectionInvitation): Unit = {
    st.phoneNumber match {
      case Some(phoneNo) =>
        val inviteURL = prepareInviteUrl(st.invitation)
        ctx.signal(Signal.SendSMSInvite(st.invitation.`@id`, inviteURL, st.label, phoneNo))
      case None =>
        ctx.signal(smsNoPhoneNumberSignal)
    }
  }

  def prepareConnectionInvitation(st:State.Created): Invitation = {
    Invitation(
      st.label,
      ctx.serviceEndpoint,
      Vector(st.verKey),
      Option(Vector(st.agencyVerKey)),
      blankOption(st.profileUrl)
    )
  }

  def outOfBandInvitation(st: State.Created, m: Ctl.OutOfBandInvitation): Unit = {
    createInviteCreatedEvent(st)

    val invitationMsg = genOutOfBandInvitation(
      st.label,
      m.goalCode,
      m.goal,
      Vector.empty,
      st.did,
      st.verKey,
      st.agencyVerKey,
      blankOption(st.profileUrl),
      blankOption(st.publicDid)
    )

    val inviteURL = prepareInviteUrl(invitationMsg, "oob")
    if (m.shortInvite.getOrElse(defaultShortInviteOption))
      ctx.urlShortening.shorten(inviteURL)(shortenerHandler(invitationMsg.`@id`))
    else
      ctx.signal(Signal.Invitation(inviteURL, None, invitationMsg.`@id`))
  }

  def outOfBandInvitation(st: State.InvitationCreated, m: Ctl.OutOfBandInvitation): Unit = {
    val invitationMsg = genOutOfBandInvitation(
      st.label,
      m.goalCode,
      m.goal,
      Vector.empty,
      st.did,
      st.verKey,
      st.agencyVerKey,
      st.invitation.profileUrl,
      blankOption(st.publicDid)
    )

    val inviteURL = prepareInviteUrl(invitationMsg, "oob")
    if (m.shortInvite.getOrElse(defaultShortInviteOption))
      ctx.urlShortening.shorten(inviteURL)(shortenerHandler(invitationMsg.`@id`))
    else
      ctx.signal(Signal.Invitation(inviteURL, None, invitationMsg.`@id`))
  }

  def outOfBandInvitation(st: State.Created, m: Ctl.SMSOutOfBandInvitation): Unit = {
    st.phoneNumber match {
      case Some(phoneNo) =>
        createInviteCreatedEvent(st)

        val invitationMsg = genOutOfBandInvitation(
          st.label,
          m.goalCode,
          m.goal,
          Vector.empty,
          st.did,
          st.verKey,
          st.agencyVerKey,
          blankOption(st.profileUrl),
          blankOption(st.publicDid)
        )

        val inviteURL = prepareInviteUrl(invitationMsg, "oob")
        ctx.signal(Signal.SendSMSInvite(invitationMsg.`@id`, inviteURL, st.label, phoneNo))
      case None =>
        ctx.signal(smsNoPhoneNumberSignal)
    }
  }

  def outOfBandInvitation(st: State.InvitationCreated, m: Ctl.SMSOutOfBandInvitation): Unit = {
    st.phoneNumber match {
      case Some(phoneNo) =>
        val invitationMsg = genOutOfBandInvitation(
          st.label,
          m.goalCode,
          m.goal,
          Vector.empty,
          st.did,
          st.verKey,
          st.agencyVerKey,
          st.invitation.profileUrl,
          blankOption(st.publicDid)
        )

        val inviteURL = prepareInviteUrl(invitationMsg, "oob")
        ctx.signal(Signal.SendSMSInvite(invitationMsg.`@id`, inviteURL, st.label, phoneNo))
      case None =>
        ctx.signal(smsNoPhoneNumberSignal)
    }
  }

  def genOutOfBandInvitation(label: String, goalCode: String, goal: String, requestAttach: Vector[String], did: DID,
                             verKey: VerKey, agencyVerKey: String, profileUrl: Option[String],
                             publicDid: Option[DID]): OutOfBandInvitation = {
    val routingKeys = Vector(verKey, agencyVerKey)
    val handshakeProtocols = Vector("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/")
    val service = DIDDoc(did, verKey, ctx.serviceEndpoint, routingKeys).toDIDDocFormatted.service

    OutOfBandInvitation(
      label,
      goalCode,
      goal,
      handshakeProtocols,
      requestAttach,
      service,
      profileUrl,
      publicDid.map(d => "did:sov:" + d)
    )
  }

  def prepareInviteUrl(invitation: Msg.BaseInvitation, queryName: String = "c_i"): String = {
    val inv = DefaultMsgCodec.toJson(invitation)
    ctx.serviceEndpoint + s"?${queryName}=" + Base64Util.getBase64UrlEncoded(inv.getBytes)
  }

  def createInviteCreatedEvent(st: State.Created): Unit = {
    val verKeys = Vector(st.verKey)
    val routingKeys = Vector(st.verKey, st.agencyVerKey)
    val inviteCreatedEvent = InvitationCreated(st.label, ctx.serviceEndpoint, verKeys, routingKeys)
    ctx.apply(inviteCreatedEvent)
  }

  def smsNoPhoneNumberSignal: Signal.ProblemReport = Signal.buildProblemReport(
    "Unable to send SMS because no phone number defined for relationship",
    noPhoneNumberDefined
  )

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
      (State.KeyCreationInProgress(cpk.label, st.agencyVerKey, cpk.profileUrl, st.publicDid, blankOption(cpk.phoneNumber)),
        roster
          .withAssignment(Role.Provisioner -> roster.selfIndex_!)
          .withAssignment(Role.Requester   -> roster.otherIndex())
      )
    case (st: State.KeyCreationInProgress  , _ , e: PairwiseKeyCreated   ) =>
      State.Created(e.label, e.did, e.verKey, st.agencyVerKey, st.profileUrl, st.publicDid, st.phoneNumber)

    case ( st: State.Created , _ , e: InvitationCreated ) =>
      State.InvitationCreated(Invitation(e.label, e.serviceEndpoint, e.recipientKeys.toVector,
        Option(e.routingKeys.toVector), blankOption(st.profileUrl)),
        st.label, st.did, st.verKey, st.agencyVerKey, st.publicDid, st.phoneNumber)
  }

  def initialize( paramMap: Seq[InitParamBase]): Roster[Role] = {
    ctx.updatedRoster(paramMap)
  }

  override def handleProtoMsg: (State, Option[Role], Msg) ?=> Any = ???

  def shortenerHandler(inviteId: String): Try[InviteShortened] => Unit = {
    val handler = (msg: Try[InviteShortened]) => msg match {
      case Success(m) =>
        ctx.signal(Signal.Invitation(m.longInviteUrl, Option(m.shortInviteUrl), inviteId))
      case Failure(_) =>
        ctx.signal(Signal.buildProblemReport("Shortening failed", shorteningFailed))
    }
    handler
  }

}

sealed trait Role
object Role {
  case object Provisioner extends Role
  case object Requester extends Role
}

trait RelationshipEvent

