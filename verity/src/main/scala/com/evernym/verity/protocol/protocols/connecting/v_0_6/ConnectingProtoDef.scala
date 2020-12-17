package com.evernym.verity.protocol.protocols.connecting.v_0_6

import com.evernym.verity.actor.ConnectionStatusUpdated
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily.pairwise._
import com.evernym.verity.agentmsg.msgpacker.AgentMsgWrapper
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.actor.{Init, ProtoMsg, UpdateMsgDeliveryStatus}
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.util.getNewActorIdFromSeed
import com.evernym.verity.protocol.engine.{MsgName, _}
import com.evernym.verity.protocol.protocols.connecting.common._
import com.evernym.verity.protocol.protocols.{MsgSendingFailed, MsgSentSuccessfully}
import com.evernym.verity.util.Util._


trait ConnectingRole

object ConnectingMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.EVERNYM_QUALIFIER
  override val name: MsgFamilyName = MSG_FAMILY_CONNECTING
  override val version: MsgFamilyVersion = MFV_0_6

  override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map (
    "CONN_REQ_ACCEPTED"     -> classOf[ConnReqAcceptedMsg_MFV_0_6],
    "CONN_REQ_DECLINED"     -> classOf[ConnReqDeclinedMsg_MFV_0_6],
    "CONN_REQ_REDIRECTED"   -> classOf[ConnReqRedirectedMsg_MFV_0_6],
    "AgentMsgWrapper"       -> classOf[AgentMsgWrapper],        //doesn't belong to here, should refactor
  )

  override protected val controlMsgs: Map[MsgName, Class[_]] = Map (
    "CREATE_KEY"                -> classOf[CreateKeyReqMsg_MFV_0_6],
    "CREATE_CONNECTION"         -> classOf[CreateConnectionReqMsg_MFV_0_6],
    "CONN_REQUEST"              -> classOf[ConnReqMsg_MFV_0_6],
    "ACCEPT_CONN_REQ"           -> classOf[AcceptConnReqMsg_MFV_0_6],
    "DECLINE_CONN_REQ"          -> classOf[DeclineConnReqMsg_MFV_0_6],
    "REDIRECT_CONN_REQ"         -> classOf[RedirectConnReqMsg_MFV_0_6],
    "get-status"                -> classOf[GetStatusReqMsg_MFV_0_6],
    "send-conn-req-msg"         -> classOf[SendConnReqMsg],
    "send-msg-to-reg-endpoint"  -> classOf[SendMsgToRegisteredEndpoint],
    "MsgSentSuccessfully"       -> classOf[MsgSentSuccessfully],
    "MsgSendingFailed"          -> classOf[MsgSendingFailed],
    "SendMsgToEdgeAgent"        -> classOf[SendMsgToEdgeAgent],
    "SendMsgToRemoteCloudAgent" -> classOf[SendMsgToRemoteCloudAgent],
    "UpdateMsgDeliveryStatus"   -> classOf[UpdateMsgDeliveryStatus],
    "UpdateMsgExpirationTime"   -> classOf[UpdateMsgExpirationTime_MFV_0_6]
  )

  override protected val signalMsgs: Map[Class[_], MsgName] = Map (
    classOf[KeyCreatedRespMsg_MFV_0_6]      -> "KEY_CREATED",
    classOf[ConnReqRespMsg_MFV_0_6]         -> "CONN_REQUEST_RESP",
    classOf[AcceptConnReqRespMsg_MFV_0_6]   -> "ACCEPT_CONN_REQ_RESP",
    classOf[ConnReqRedirectResp_MFV_0_6]    -> "CONN_REQ_REDIRECTED",
    classOf[StatusReport]                   -> "status-report",
    classOf[AcceptedInviteAnswerMsg_0_6]    -> "CONN_REQ_ACCEPTED",
    classOf[RedirectedInviteAnswerMsg_0_6]  -> "CONN_REQ_REDIRECTED",
    classOf[ConnReqReceived]                -> "conn-req-received",
    classOf[SendMsgToRegisteredEndpoint]    -> "send-msg-to-registered-endpoint",
    classOf[ConnectionStatusUpdated]        -> "connection-status-updated",
    classOf[NotifyUserViaPushNotif]         -> "NotifyUserViaPushNotif",
    classOf[AddMsg]                         -> "AddMsg",
    classOf[UpdateMsg]                      -> "UpdateMsg",
    classOf[UpdateDeliveryStatus]           -> "UpdateDeliveryStatus"
  )
}

object ConnectingProtoDef
  extends ProtocolDefinition[ConnectingProtocol, Role, ProtoMsg, Any, ConnectingState, String] {

  val msgFamily: MsgFamily = ConnectingMsgFamily

  override val roles: Set[Role] = Set.empty

  override lazy val initParamNames: Set[String] = Set(
    NAME,
    LOGO_URL,
    THIS_AGENT_WALLET_SEED,
    AGENCY_DID,
    AGENCY_DID_VER_KEY,
    CREATE_KEY_ENDPOINT_SETUP_DETAIL_JSON,
    MY_PUBLIC_DID,
    MY_SELF_REL_DID
  )

  override def supportedMsgs: ProtoReceive = {

    case amw: AgentMsgWrapper if amw.isMatched(msgFamily.version, MSG_TYPE_CREATE_CONNECTION) =>

    case amw: AgentMsgWrapper if amw.isMatched(msgFamily.version, MSG_TYPE_CREATE_KEY) =>

    case amw: AgentMsgWrapper if amw.isMatched(msgFamily.version, MSG_TYPE_CONN_REQ) =>

    case amw: AgentMsgWrapper if amw.isMatched(msgFamily.version, MSG_TYPE_ACCEPT_CONN_REQ) =>

    case amw: AgentMsgWrapper if amw.isMatched(msgFamily.version, MSG_TYPE_REDIRECT_CONN_REQ) =>

    case amw: AgentMsgWrapper if amw.isMatched(msgFamily.version, MSG_TYPE_DECLINE_CONN_REQ) =>

    case amw: AgentMsgWrapper if amw.isMatched(msgFamily.version, MSG_TYPE_CONN_REQ_ACCEPTED) =>

    case amw: AgentMsgWrapper if amw.isMatched(msgFamily.version, MSG_TYPE_CONN_REQ_REDIRECTED) =>

    case amw: AgentMsgWrapper if amw.isMatched(msgFamily.version, MSG_TYPE_CONN_REQ_DECLINED) =>

    case _: GetInviteDetail_MFV_0_6 =>

    case UpdateMsgExpirationTime_MFV_0_6(CREATE_MSG_TYPE_CONN_REQ, _) =>

  }

  override def createInitMsg(params: Parameters): Control = Init(params)

  override def protocolIdSuffix(typedMsg: TypedMsgLike): Option[String] = {
    (typedMsg.msgType.msgName, typedMsg.msg) match {
      case (MSG_TYPE_CREATE_CONNECTION, amw: AgentMsgWrapper) =>
        val ccm = CreateConnectionMsgHelper.buildReqMsgFrom_MFV_0_6(amw)
        Option(getNewActorIdFromSeed(ccm.sourceId))

      case (MSG_TYPE_CONNECTING_GET_STATUS, amw: AgentMsgWrapper) =>
        val gs = GetStatusMsgHelper.buildReqMsgFrom_MFV_0_6(amw)
        Option(getNewActorIdFromSeed(gs.sourceId))

      case (MSG_TYPE_CREATE_KEY, _) => Option(getNewActorId)

      case _ => None
    }
  }

  override def create(context: ProtocolContextApi[ConnectingProtocol, Role, ProtoMsg, Any, ConnectingState, String]):
      Protocol[ConnectingProtocol, Role, ProtoMsg, Any, ConnectingState, String] =
    new ConnectingProtocol(context)

  override def initialState: ConnectingState = ConnectingState(null, null)
}
