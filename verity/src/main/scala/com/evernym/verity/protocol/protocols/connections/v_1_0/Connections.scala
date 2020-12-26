package com.evernym.verity.protocol.protocols.connections.v_1_0

import java.nio.charset.StandardCharsets
import java.nio.{ByteBuffer, ByteOrder}
import java.time.Instant

import com.evernym.verity.ServiceEndpoint
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.external_api_access.WalletAccess.SIGN_ED25519_SHA512_SINGLE
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.engine.{Protocol, ProtocolContextApi, _}
import com.evernym.verity.protocol.protocols.CommonProtoTypes.SigBlockCommunity
import com.evernym.verity.protocol.protocols.connections.v_1_0.Connections.InvalidSigException
import com.evernym.verity.protocol.protocols.connections.v_1_0.Ctl.TheirDidDocUpdated
import com.evernym.verity.protocol.protocols.connections.v_1_0.Role.{Invitee, Inviter}
import com.evernym.verity.protocol.protocols.connections.v_1_0.Signal.SetupTheirDidDoc
import com.evernym.verity.util.Base64Util
import com.evernym.verity.UrlDetail
import com.evernym.verity.actor.agent.relationship.URL
import com.evernym.verity.protocol.engine.external_api_access.WalletAccess

import scala.util.{Failure, Success, Try}


class Connections(val ctx: ProtocolContextApi[Connections, Role, Msg, Event, State, String])
  extends Protocol[Connections, Role, Msg, Event, State, String](ConnectionsDef) {

  def handleControl: Control ?=> Any = {
    case c: Control => mainHandleControl(ctx.getState, ctx.getRoster.selfRole, c)
  }

  // Control Message Handlers
  def mainHandleControl: (State, Option[Role], Control) ?=> Any = {
    case (_: State.Uninitialized    , _             , m: Ctl.Init         ) => initialize(m)
    case (s: State.Initialized      , _             , m: Ctl.Accept       ) => accept(s, m)
    case (s                         , Some(Invitee) , m                   ) => inviteeCtl(s, m)
    case (s                         , Some(Inviter) , m                   ) => inviterCtl(s, m)
    case (s: State                  ,_              , _: Ctl.Status       ) => handleStatus(s)
  }

  def inviteeCtl: (State, Control) ?=> Unit = {
    case (s: State.Accepted         , m: Ctl.TheirDidDocUpdated           ) => sendConnRequest(s, m)
    case (s: State.ResponseReceived , _: Ctl.TheirDidUpdated              ) => handleTheirDidUpdated(s)
  }

  def inviterCtl: (State, Control) ?=> Unit = {
    case (s: State.RequestReceived  , m: Ctl.TheirDidDocUpdated           ) => sendConnResponse(s, m)
  }

  // Protocol Msg Handlers
  override def handleProtoMsg: (State, Option[Role], Msg) ?=> Any = {
    case (_                       , _             , m: Msg.ProblemReport  ) => handleProblemReport(m)
    case (_: State.Initialized    , _             , m: Msg.ConnRequest    ) => receivedConnRequest(m)
    case (s: State.RequestSent    , _             , m: Msg.ConnResponse   ) => receivedConnResponse(s, m)
    case (s                       , Some(Invitee) , m                     ) => fromInviteeProtoMsgHandler(s, m)
    case (s                       , Some(Inviter) , m                     ) => fromInviterProtoMsgHandler(s, m)
  }

  /**
   * protocol messages sent by Invitee
   * @return
   */
  def fromInviteeProtoMsgHandler: (State, Msg) ?=> Any = {
    case (s: State.ResponseSent , m: Msg.Ack               ) => receivedAck(s, m)
  }

  /**
   * protocol messages sent by Inviter
   * @return
   */

  def fromInviterProtoMsgHandler: (State, Msg) ?=> Any = {
    case (s: State.RequestSent  , m: Msg.ConnResponse ) => receivedConnResponse(s, m)
  }

  def initialize(m: Ctl.Init): Unit = {
    ctx.apply(Initialized(m.params.initParams.map(p => InitParam(p.name, p.value)).toSeq))
  }

  def handleStatus(s: State): Unit = {
    ctx.signal(Signal.StatusReport(s.getClass.getSimpleName))
  }

  def invitationReceived(inviteUrl: String): Option[State.Invited] = {
    try {
      val decodedUrl = Connections.getInvitationJsonFromUrl(inviteUrl).get
      // Question: What should the validation be? What endpoints would be valid? What keys would be valid?
      val inv = DefaultMsgCodec.fromJson[Msg.InviteWithDID](decodedUrl)
      if (inv.did == null) {
        val m = DefaultMsgCodec.fromJson[Msg.InviteWithKey](decodedUrl)
        val event = InvitedWithKey(Some(PreparedWithKey(m.label, m.serviceEndpoint, m.recipientKeys, m.routingKeys_!)), 2)
        ctx.apply(event)
        ctx.signal(Signal.InvitedWithKey(m))
        Option(stateByInvitationType(event))
      } else {
        ctx.signal(Signal.UnhandledError("not supporting invite with DID"))
        None
      }
    } catch {
      case _: Connections.InvalidInviteException =>
        ctx.signal(Signal.InvalidInvite(inviteUrl))
        //shall we send any protocol message to the inviter?
        None
    }
  }

  def accept(s: State.Initialized, a: Ctl.Accept): Unit = {
    invitationReceived(a.invite_url).foreach { st =>
      st.inv match {
        case Left(_: Msg.InviteWithDID) =>
          ctx.signal(Signal.UnhandledError("not supporting invite with DID"))
        case Right(m: Msg.InviteWithKey) if m.recipientKeys.size > 1 =>
          ctx.signal(Signal.UnhandledError("not supporting more than one ver keys"))
        case Right(m: Msg.InviteWithKey) =>
          val myDID = ctx.getRoster.selfId_!
          ctx.wallet.verKey(myDID) match {
            case Success(myVerKey) =>
              ctx.apply(InviteAccepted(Some(ProvisionalRelationship(myDID, myVerKey, ctx.serviceEndpoint,
                m.recipientKeys, m.routingKeys_!, m.serviceEndpoint)), a.label))
              ctx.signal(SetupTheirDidDoc(myDID, m.recipientKeys.head, m.serviceEndpoint, m.routingKeys_!, None))
            case Failure(_) =>
              ctx.signal(Signal.UnhandledError("error while accepting invitation"))
              //shall we send any protocol message to the inviter (invitation and connections are different protocols and thread)?
          }
      }
    }
  }

  def sendConnRequest(state: State.Accepted, m: TheirDidDocUpdated): Unit = {
    val rel = state.rel
    val didDoc = DIDDoc(rel.did, rel.verKey, ctx.serviceEndpoint, m.myRoutingKeys)
    val req = Msg.ConnRequest(state.label, Msg.Connection(rel.did, didDoc.toDIDDocFormatted))
    ctx.send(req)
    ctx.apply(RequestSent(Some(ProvisionalRelationship(rel.did, rel.verKey, ctx.serviceEndpoint,
      rel.theirVerKeys, rel.theirRoutingKeys, rel.theirEndpoint))))
    ctx.signal(Signal.ConnRequestSent(req))
  }

  def sendConnResponse(state: State.RequestReceived, m: Ctl.TheirDidDocUpdated): Unit = {
    val rel = state.rel
    val didDoc = DIDDoc(rel.myDid, rel.myVerKey, rel.myEndpoint, m.myRoutingKeys)
    val conn = Msg.Connection(rel.myDid, didDoc.toDIDDocFormatted)
    Connections.buildConnectionSig(conn, rel.myVerKey, ctx.wallet) match {
      case Success(sigBlock: SigBlockCommunity) =>
        ctx.apply(ResponseSent(Some(Relationship(rel.myDid, rel.myVerKey, rel.myEndpoint,
          rel.theirDid, rel.theirVerKey, rel.theirEndpoint))))
        val resp = Msg.ConnResponse(sigBlock)
        ctx.send(resp)
        ctx.signal(Signal.ConnResponseSent(resp, rel.myDid))
      case Failure(_) =>
        sendProblemReport("request_processing_error", "error while processing request")
    }
  }

  // Event Handlers
  def applyEvent: ApplyEvent = applyCommonEvt orElse roleBasedEventApplier

  def roleBasedEventApplier: ApplyEvent =
    ctx.getRoster.selfRole match {
      case Some(Role.Invitee) => applyInviteeEvt
      case Some(Role.Inviter) => applyInviterEvt
      case _                  => PartialFunction.empty
    }

  def applyCommonEvt: ApplyEvent = {
    case (_: State.Uninitialized , _ , e: Initialized ) => (State.Initialized(), onInitialized(e))

    case ( State.Initialized() , _ , e @ ( _: InvitedWithDID | _:InvitedWithKey) ) =>
      val state = stateByInvitationType(e)
      val roster = ctx.getRoster
      (state, roster.withAssignment(Role.Invitee -> roster.selfIndex_!))

    case ( _: State.Initialized , _ , e: RequestReceived ) =>
      val rel = e.rel.get
      val roster: Roster[Role] =
        Roster()
          .withParticipant(ctx.getRoster.selfId_!, isSelf = true) //TODO: confirm about this: self participant id changed
          .withParticipant(rel.theirDid)

      (State.RequestReceived(Msg.Relationship(rel)),
        roster
          .withAssignment(Role.Inviter -> roster.selfIndex_!)
          .withAssignment(Role.Invitee -> roster.participantIndex_!(rel.theirDid))
      )
  }

  def onInitialized(i: Initialized): Roster[Role] = {
    val paramMap = i.params map { p => InitParamBase(p.name, p.value) }
    ctx.updatedRoster(paramMap)
  }

  def applyInviterEvt: ApplyEvent = {

    case (_: State.RequestReceived, _, e: ResponseSent)  =>
      val rel = e.rel.get
      val relObj = Msg.Relationship(rel)
      State.ResponseSent(relObj)

    case (s: State.ResponseSent, _, _: AckReceived)  =>
      val rel = s.rel
      State.Completed(rel)
  }

  def applyInviteeEvt: ApplyEvent = {

    case ( _: State.Invited , _, e: InviteAccepted) =>
      val rel = e.provRel.get
      val roster: Roster[Role] = Roster()
        .withParticipant(rel.did, isSelf = true)    //TODO: confirm about this: self participant id changed
        .withParticipant(ctx.getRoster.otherId())

      (State.Accepted(Msg.ProvisionalRelationship(rel), e.label),
        roster.withAssignment(Role.Invitee -> roster.selfIndex_!))

    case ( _: State.Accepted , _ , e: RequestSent) =>
      val rel = e.provRel.get
      State.RequestSent(Msg.ProvisionalRelationship(rel))

    case (_: State.RequestSent, _, e: ResponseReceived) =>
      val rel = e.rel.get

      val roster: Roster[Role] = Roster()
        .withParticipant(ctx.getRoster.selfId_!, isSelf = true)
        .withParticipant(rel.theirDid)    //TODO: confirm about this: self participant id changed

      (State.ResponseReceived(Msg.Relationship(rel)),
        roster
          .withAssignment(Role.Invitee -> roster.selfIndex_!)
          .withAssignment(Role.Inviter -> roster.otherIndex()))

    case (rr: State.ResponseReceived, r, _: AckSent) =>
      State.Completed(rr.rel)
  }

  def stateByInvitationType: PartialFunction[Any, State.Invited] = {
    case InvitedWithDID(Some(inv: PreparedWithDID), _) =>
      State.Invited(Left(Msg.InviteWithDID(inv.did, inv.label)))
    case InvitedWithKey(Some(inv: PreparedWithKey), _) =>
      State.Invited(Right(Msg.InviteWithKey(inv.serviceEndpoint, inv.recipientKeys.toVector, Option(inv.routingKeys.toVector), inv.label)))
  }

  def handleProblemReport(m: Msg.ProblemReport): Unit = {
    // TODO
  }

  def sendProblemReport(`problem-code`: String, explain: String, endpoint: Option[ServiceEndpoint]=None): Unit = {
    val m = Msg.ProblemReport(`problem-code`, explain)
    endpoint match {
      case Some(_) => ctx.send(m)
      case None => ???  // TODO: Send problem report to the other protocol
    }
  }

  def sendAck(isCompleted: Boolean): Unit = {
    val ack = Msg.Ack(isCompleted)
    if (isCompleted) {
      ctx.apply(AckSent())
    }
    ctx.send(ack)
  }

  def receivedAck(s: State.ResponseSent, m: Msg.Ack): Unit = {
    ctx.logger.trace("Received Ack", m)
    if (m.status) {
      ctx.apply(AckReceived())
      ctx.signal(Signal.Complete(s.rel.theirDid))
    }
  }

  def receivedConnRequest(m: Msg.ConnRequest): Unit = {
    val myDID = ctx.getRoster.selfId_!
    ctx.signal(Signal.ConnRequestReceived(m.connection, myDID))
    ctx.wallet.verKey(myDID) match {
      case Success(myVerKey) =>
        val theirDidDoc = m.connection.did_doc.toDIDDoc
        // this prioritizes the id in the DIDDoc over the DID in the message
        //
        // [NOT SURE] this fixes a missing id in the DIDDoc for the purpose of theirDid variable
        // But we still have a hole in the DID itself. We don't currently use the DID outside its parts
        // so it is fine for now.
        val theirDiD = Option(theirDidDoc.getDID).getOrElse(m.connection.DID)
        ctx.wallet.storeTheirDid(theirDiD, theirDidDoc.getVerkey)
        val rel = Relationship(myDID, myVerKey, ctx.serviceEndpoint, theirDiD, theirDidDoc.getVerkey, theirDidDoc.getEndpoint, theirDidDoc.routingKeys)
        ctx.apply(RequestReceived(Some(rel)))
        ctx.signal(Signal.SetupTheirDidDoc(myDID, theirDidDoc.verkey, theirDidDoc.endpoint, theirDidDoc.routingKeys, Option(theirDiD)))
      case Failure(_) =>
        sendProblemReport("request_processing_error", "error while processing request")
    }
  }

  def receivedConnResponse(s: State.RequestSent, m: Msg.ConnResponse): Unit = {
    Connections.buildConnFromConnSig(m.`connection~sig`, ctx.wallet) match {
      case Success(conn) =>
        val theirDidDoc = conn.did_doc.toDIDDoc
        ctx.signal(Signal.ConnResponseReceived(conn))
        ctx.apply(ResponseReceived(Some(Relationship(s.rel.did, s.rel.verKey, s.rel.endpoint, theirDidDoc.getDID,
          theirDidDoc.getVerkey, theirDidDoc.getEndpoint, theirDidDoc.routingKeys))))
        ctx.signal(Signal.UpdateTheirDid(s.rel.did, theirDidDoc.id))
      case Failure(ex: InvalidSigException) =>
        sendProblemReport(ex.`problem-report`, ex.explain, Some(s.rel.theirEndpoint))
        ctx.signal(Signal.ConnResponseInvalid())
      case Failure(_) =>
        sendProblemReport("response_processing_error", "error while processing response", Some(s.rel.theirEndpoint))
    }
  }

  def handleTheirDidUpdated(s: State.ResponseReceived): Unit = {
    sendAck(isCompleted=true)
    ctx.signal(Signal.Complete(s.rel.theirDid))
  }
}

object Connections {

  case class InvalidInviteException(explain: String, `problem-report`: String = "invalid_invitation") extends Exception
  case class InvalidSigException(explain: String, `problem-report`: String = "response_not_accepted") extends Exception

  def getInvitationJsonFromUrl(inviteURL: URL): Try[String] = {
    val urlDetail = UrlDetail(inviteURL)
    val invB64 = if (urlDetail.isHttp || urlDetail.isHttps) {
      urlDetail.query match {
        case Some(q) =>
          if (q.startsWith("c_i=")) {
            q.split("c_i=", 2).last
          } else {
            throw InvalidInviteException("URL should have a query string starting with c_i=")
          }
        case None =>
          throw InvalidInviteException("URL should have a query string")
      }
    } else {
      throw InvalidInviteException("invalid_invitation", "URL should be either HTTP or HTTPS")
    }
    val invJsonBytes = Base64Util.getBase64UrlDecoded(invB64)
    Try(new String(invJsonBytes, StandardCharsets.UTF_8))
  }

  def curTimeStampBytes(): Array[Byte] = {
    val bb = ByteBuffer
      .allocate(8)
      .order(ByteOrder.BIG_ENDIAN)
      .putLong(Instant.now.getEpochSecond)
    bb.array
  }

  def buildConnectionSig(conn: Msg.Connection, verKey: String, wallet: WalletAccess): Try[SigBlockCommunity] = {
    val conn_str = DefaultMsgCodec.toJson(conn)
    val bytes = curTimeStampBytes() ++ conn_str.getBytes
    val b64_encoded = Base64Util.getBase64UrlEncoded(bytes)
    wallet.sign(bytes)
      .map{ sig =>
        SigBlockCommunity(
          sig.toBase64UrlEncoded,
          b64_encoded,
          sig.verKey
        )
      }
  }

  // Build a Connection object from a SigBlockCommunity. Verifies the signature as well and returns an error
  // if the signature is not verified or the verkey present in the SigBlockCommunity is different from the
  // one in the DID doc in Connection
  def buildConnFromConnSig(sigBlock: SigBlockCommunity, wallet: WalletAccess): Try[Msg.Connection] = {
    val conn_bytes = Base64Util.getBase64UrlDecoded(sigBlock.sig_data)
    val connJson = new String(conn_bytes.drop(8), StandardCharsets.UTF_8) //dropping timestamp portion
    val conn = DefaultMsgCodec.fromJson[Msg.Connection](connJson)
    val isVerified =
    wallet.verify(
      conn_bytes,
      Base64Util.getBase64UrlDecoded(sigBlock.signature),
      sigBlock.signer,
      SIGN_ED25519_SHA512_SINGLE
    ).recover {
      case ex =>
        throw InvalidSigException(s"Error during exception - ${ex.getMessage}")
    }.get
    if (!isVerified) {
      throw InvalidSigException("Signature verification failed")
    }
    val theirDidDoc = conn.did_doc.toDIDDoc
    val (theirDid, theirVerKey) = (theirDidDoc.getDID, theirDidDoc.getVerkey)
    wallet.storeTheirDid(theirDid, theirVerKey)
    Try(conn)
  }
}
