package com.evernym.verity.protocol.protocols.connecting.v_0_5

import com.evernym.verity.actor.ConnectionStatusUpdated
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily.pairwise._
import com.evernym.verity.agentmsg.msgpacker.AgentMsgWrapper
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.container.actor.{Init, ProtoMsg, UpdateMsgDeliveryStatus}
import com.evernym.verity.protocol.engine.Constants.MFV_0_5
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.connecting.common._
import com.evernym.verity.protocol.protocols.{MsgSendingFailed, MsgSentSuccessfully}

object ConnectingMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.EVERNYM_QUALIFIER
  override val name: MsgFamilyName = MSG_FAMILY_CONNECTING
  override val version: MsgFamilyVersion = MFV_0_5

  override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    "AgentMsgWrapper"         -> classOf[AgentMsgWrapper],          //doesn't belong to here, should refactor
    "connReqAnswer"           -> classOf[AnswerMsgReqMsg_MFV_0_5],
    "connReqRedirected"       -> classOf[RedirectedMsgReqMsg_MFV_0_5],
    "GetInviteDetail"         -> classOf[GetInviteDetail_MFV_0_5],
  )

  override protected val controlMsgs: Map[MsgName, Class[_]] = Map (
    "connReq"                   -> classOf[CreateMsgReqMsg_MFV_0_5],
    "connReqRedirect"           -> classOf[RedirectMsgReqMsg_MFV_0_5],
    "SendConnReqMsg"            -> classOf[SendConnReqMsg],
    "UpdateMsgDeliveryStatus"   -> classOf[UpdateMsgDeliveryStatus],
    "SendMsgToRemoteCloudAgent" -> classOf[SendMsgToRemoteCloudAgent],
    "MsgSentSuccessfully"       -> classOf[MsgSentSuccessfully],
    "MsgSendingFailed"          -> classOf[MsgSendingFailed],
    "SendMsgToEdgeAgent"        -> classOf[SendMsgToEdgeAgent],
    "UpdateMsgExpirationTime"   -> classOf[UpdateMsgExpirationTime_MFV_0_5]
  )

  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
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
    MY_PAIRWISE_DID,
    MY_PAIRWISE_DID_VER_KEY,
    MY_PUBLIC_DID,
    THIS_AGENT_VER_KEY,
    THIS_AGENT_WALLET_ID,
    AGENCY_DID,
    AGENCY_DID_VER_KEY,
    MY_SELF_REL_DID
  )

  override def supportedMsgs: ProtoReceive = {

    case amw: AgentMsgWrapper if amw.isMatched(msgFamily.version, CREATE_MSG_TYPE_CONN_REQ) =>

    case amw: AgentMsgWrapper if amw.isMatched(msgFamily.version, CREATE_MSG_TYPE_CONN_REQ_ANSWER) =>

    case amw: AgentMsgWrapper if amw.isMatched(msgFamily.version, CREATE_MSG_TYPE_REDIRECT_CONN_REQ) =>

    case amw: AgentMsgWrapper if amw.isMatched(msgFamily.version, CREATE_MSG_TYPE_CONN_REQ_REDIRECTED) =>

    case _: GetInviteDetail_MFV_0_5 =>

    case UpdateMsgExpirationTime_MFV_0_5(CREATE_MSG_TYPE_CONN_REQ, _) =>
  }

  override def createInitMsg(params: Parameters): Control = Init(params)

  override def create(context: ProtocolContextApi[ConnectingProtocol, Role, ProtoMsg, Any, ConnectingState, String]):
  Protocol[ConnectingProtocol, Role, ProtoMsg, Any, ConnectingState, String] =
    new ConnectingProtocol(context)

  override def initialState: ConnectingState = ConnectingState()
}
