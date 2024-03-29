package com.evernym.verity.protocol.protocols.connecting.v_0_6

import com.evernym.verity.actor.agent.MsgPackFormat.{MPF_INDY_PACK, MPF_MSG_PACK, MPF_PLAIN, Unrecognized}
import com.evernym.verity.actor.agent.{AgentDetail, MsgSendingFailed, MsgSentSuccessfully}
import com.evernym.verity.actor.wallet._
import com.evernym.verity.actor.{ActorMessage, AgentDetailSet, KeyCreated}
import com.evernym.verity.agentmsg.msgfamily.AgentMsgContext
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily.pairwise._
import com.evernym.verity.agentmsg.msgpacker.AgentMsgPackagingUtil._
import com.evernym.verity.agentmsg.msgpacker.AgentMsgWrapper
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.MsgName
import com.evernym.verity.did.didcomm.v1.messages.{MsgFamily, MsgId}
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.protocol._
import com.evernym.verity.protocol.container.actor.UpdateMsgDeliveryStatus
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.context.ProtocolContextApi
import com.evernym.verity.protocol.engine.events.ParameterStored
import com.evernym.verity.protocol.engine.msg.Init
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.connecting.common._
import com.evernym.verity.util.MsgIdProvider
import com.evernym.verity.util.Util._
import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status.KEY_ALREADY_CREATED
import com.evernym.verity.vault._

import scala.concurrent.{ExecutionContext, Future}


//noinspection ScalaDeprecation
class ConnectingProtocol(val ctx: ProtocolContextApi[ConnectingProtocol, Role, ProtoMsg, Any, ConnectingState, String])
    extends Protocol[ConnectingProtocol,Role,ProtoMsg,Any,ConnectingState,String](ConnectingProtoDef)
      with ConnectingProtocolBase[ConnectingProtocol,Role,ConnectingState,String] {

  implicit lazy val futureExecutionContext: ExecutionContext = ctx.executionContext

  lazy val myPairwiseDIDReq: DidStr = ctx.getState.myPairwiseDIDReq
  lazy val myPairwiseVerKeyReq: VerKeyStr = getVerKeyReqViaCache(ctx.getState.myPairwiseDIDReq).verKey

  def initState(params: Seq[ParameterStored]): ConnectingState = {
    val seed = params.find(_.name == THIS_AGENT_WALLET_ID).get.value
    initWalletDetail(seed)
    ConnectingState(isInitialized=true)
  }

  override def applyEvent: ApplyEvent =
    applyLocalEvent orElse applyEventBase


  def applyLocalEvent: ApplyEvent = {
    case (s, _, ads: AgentDetailSet) =>
      s.thisAgentVerKeyOpt = Option(ctx.DEPRECATED_convertAsyncToSync(
        walletAPI.executeAsync[GetVerKeyResp](GetVerKey(ads.agentKeyDID))).verKey)
      s.agentDetail = Option(AgentDetail(ads.forDID, ads.agentKeyDID))
      s

    case (s, _, _: KeyCreated) =>
      s
  }

  //TODO feels like these are control messages, and there are protocol messages going into control
  override def handleProtoMsg: (ConnectingState, Option[Role], ProtoMsg) ?=> Any = {

    case (_, _, amw: AgentMsgWrapper) if amw.headAgentMsgDetail.msgName == MSG_TYPE_CREATE_CONNECTION
                                                          => handleCreateConnection(amw)

    case (_, _, amw: AgentMsgWrapper) if amw.headAgentMsgDetail.msgName == MSG_TYPE_CREATE_KEY              // ie -> ic, ae -> ac
                                                          => handleCreateKey(amw)

    case (_, _, amw: AgentMsgWrapper) if amw.headAgentMsgDetail.msgName == MSG_TYPE_CONN_REQ                // ie -> ic
                                                          => handleConnReqMsg(amw)

    case (_, _, amw: AgentMsgWrapper) if amw.headAgentMsgDetail.msgName == MSG_TYPE_ACCEPT_CONN_REQ         // ae -> ac
                                                          => handleAcceptConnReqMsg(amw)

    case (_, _, amw: AgentMsgWrapper) if amw.headAgentMsgDetail.msgName == MSG_TYPE_REDIRECT_CONN_REQ       // ae -> ac
                                                          => handleRedirectConnReqMsg(amw)

    case (_, _, amw: AgentMsgWrapper) if amw.headAgentMsgDetail.msgName == MSG_TYPE_DECLINE_CONN_REQ        // ae -> ac
                                                          => handleDeclineConnReqMsg(amw)

    case (_, _, amw: AgentMsgWrapper) if amw.headAgentMsgDetail.msgName == MSG_TYPE_CONN_REQ_ACCEPTED        // ac -> ic
                                                          => handleConnReqAcceptedMsg(amw)

    case (_, _, amw: AgentMsgWrapper) if amw.headAgentMsgDetail.msgName == MSG_TYPE_CONN_REQ_REDIRECTED      // ac -> ic
                                                          => handleConnReqRedirectedMsg(amw)

    case (_, _, amw: AgentMsgWrapper) if amw.headAgentMsgDetail.msgName == MSG_TYPE_CONN_REQ_DECLINED        // ac -> ic
                                                          => handleConnReqDeclinedMsg(amw)

    case (_, _, amw: AgentMsgWrapper) if amw.headAgentMsgDetail.msgName == MSG_TYPE_CONNECTING_GET_STATUS =>
      val getStatusReport = amw.headAgentMsg.convertTo[GetStatusReqMsg_MFV_0_6]
      handleGetStatusReqMsg(getStatusReport)

    case (_, _, gid: GetInviteDetail                    ) => handleGetInviteDetail(gid)                      // ae -> ic, general thing, but this is used this way in this protocol
  }


  def handleControl: Control ?=> Any = {
    case x => handleControlExt( (ctx.getState.stateStr, ctx.getRoster.selfRole, x) )
  }

  protected def handleControlExt: (String, Option[Role], Control) ?=> Any = {

    case (_, _, ip: Init                        ) => handleInitParams(ip)                               // d -> *
    case (_, _, m: SendConnReqMsg               ) => sendConnReqMsg(m.uid)                              // ic -> ic
    case (_, _, m: SendMsgToRemoteCloudAgent    ) => sendMsgToRemoteCloudAgent(m.uid, m.msgPackFormat) // ac -> ac
    case (_, _, m: SendMsgToEdgeAgent           ) => sendMsgToEdgeAgent(m.uid)                          //ic -> ic
    case (_, _, m: MsgSentSuccessfully          ) => handleMsgSentSuccessfully(m)                       // ic -> ic, ac -> ac
    case (_, _, m: MsgSendingFailed             ) => handleMsgSendingFailed(m)                          // ic -> ic, ac -> ac
    case (_, _, m: UpdateMsgDeliveryStatus      ) => handleUpdateMsgDeliveryStatus(m)                   // ic -> ic, ac -> ac
    case (_, _, umet: UpdateMsgExpirationTime_MFV_0_6) if umet.targetMsgName == CREATE_MSG_TYPE_CONN_REQ      // ie -> ic
                                                  => handleUpdateMsgExpirationTime(umet)
    case (_, _, srm: SendMsgToRegisteredEndpoint) => ctx.signal(srm)
  }

  private def handleCreateConnection(amw: AgentMsgWrapper): PackedMsg = {
    val cc = amw.headAgentMsg.convertTo[CreateConnectionReqMsg_MFV_0_6]
    val edgePairwiseKey = ctx.DEPRECATED_convertAsyncToSync(walletAPI.executeAsync[NewKeyCreated](CreateNewKey()))
    val edgePairwiseKeyCreated = KeyCreated(edgePairwiseKey.did)
    ctx.apply(edgePairwiseKeyCreated)

    //TODO: we need to find a way to determine if verity is deployed as an edge agent
    //or cloud agent. For now, assuming if "create-connection" message is coming
    //verity is installed as an edge agent.

    val agentDetailSet = AgentDetailSet(edgePairwiseKey.did, edgePairwiseKey.did)
    ctx.apply(agentDetailSet)

    val endpointDetail = ctx.getState.parameters.paramValueRequired(CREATE_KEY_ENDPOINT_SETUP_DETAIL_JSON)
    val fut = ctx.SERVICES_DEPRECATED.connectEndpointServiceProvider.setupCreateKeyEndpoint(
      edgePairwiseKey.didPair,
      edgePairwiseKey.didPair,
      endpointDetail
    )
    val kdp = getAgentKeyDlgProof(edgePairwiseKey.verKey, edgePairwiseKey.did,
      edgePairwiseKey.verKey)(walletAPI, wap)
    val ccamw = ConnReqMsgHelper.buildConnReqAgentMsgWrapper_MFV_0_6(kdp, cc.phoneNo, cc.includePublicDID, amw)
    val pm = handleConnReqMsg(ccamw, Option(cc.sourceId))
    amw.msgPackFormat match {
      case MPF_PLAIN => // do nothing
      case MPF_INDY_PACK | MPF_MSG_PACK =>
        fut.map { _ =>
          ctx.SERVICES_DEPRECATED.msgQueueServiceProvider.addToMsgQueue(
            SendMsgToRegisteredEndpoint(MsgIdProvider.getNewMsgId, pm.msg, None)
          )
        }
      case Unrecognized(_) => throw new RuntimeException("unsupported msgPackFormat: Unrecognized can't be used here")
    }
    pm
  }

  private def handleCreateKey(amw: AgentMsgWrapper): Future[PackedMsg] = {
    implicit val amc: AgentMsgContext = amw.getAgentMsgContext
    val createKeyMsg = CreateKeyMsgHelper.buildReqMsg(amw)
    validateCreateKeyMsg(createKeyMsg)
    processKeyCreatedMsg(createKeyMsg)
  }

  private def processKeyCreatedMsg(createKeyReqMsg: CreateKeyReqMsg)(implicit agentMsgContext: AgentMsgContext): Future[PackedMsg] = {
    val pairwiseKeyResult = ctx.DEPRECATED_convertAsyncToSync(walletAPI.executeAsync[NewKeyCreated](CreateNewKey()))
    ctx.DEPRECATED_convertAsyncToSync(walletAPI.executeAsync[TheirKeyStored](StoreTheirKey(createKeyReqMsg.forDID, createKeyReqMsg.forDIDVerKey)))
    val event = AgentDetailSet(createKeyReqMsg.forDID, pairwiseKeyResult.did)
    ctx.apply(event)
    val endpointDetail = ctx.getState.parameters.paramValueRequired(CREATE_KEY_ENDPOINT_SETUP_DETAIL_JSON)
    val fut = ctx
      .SERVICES_DEPRECATED
      .connectEndpointServiceProvider
      .setupCreateKeyEndpoint(
        createKeyReqMsg.didPair,
        pairwiseKeyResult.didPair,
        endpointDetail
      )
    val pm = processCreateKeyAfterEndpointSetup(createKeyReqMsg, pairwiseKeyResult)
    fut.map(_ => pm)
  }

  private def processCreateKeyAfterEndpointSetup(createKeyReqMsg: CreateKeyReqMsg, pairwiseKeyCreated: NewKeyCreated)
                                                (implicit agentMsgContext: AgentMsgContext): PackedMsg = {
    val keyCreatedRespMsg = CreateKeyMsgHelper.buildRespMsg(pairwiseKeyCreated.did, pairwiseKeyCreated.verKey)
    val encryptInfo = EncryptParam(
      Set(KeyParam(Left(createKeyReqMsg.forDIDVerKey))),
      Option(KeyParam(Left(pairwiseKeyCreated.verKey)))
    )
    val param = buildPackMsgParam(encryptInfo, keyCreatedRespMsg, agentMsgContext.msgPackFormat == MPF_MSG_PACK)
    if (agentMsgContext.msgPackFormat == MPF_PLAIN) {
      keyCreatedRespMsg.foreach(m => ctx.signal(m))
    }

    awaitResult(buildAgentMsg(agentMsgContext.msgPackFormatToBeUsed, param)(agentMsgTransformer, wap, ctx.metricsWriter))
  }

  private def validateCreateKeyMsg(createKeymsg: CreateKeyReqMsg): Unit = {
    checkIfKeyNotCreated(createKeymsg.forDID)
  }

  private def checkIfKeyNotCreated(forDID: DidStr): Unit = {
    if (ctx.getState.agentDetail.exists(_.forDID == forDID)) {
      throw new BadRequestErrorException(KEY_ALREADY_CREATED.statusCode)
    }
  }

  def handleConnReqMsg(amw: AgentMsgWrapper, sourceId: Option[String]=None): PackedMsg = {
    implicit val amc: AgentMsgContext = amw.getAgentMsgContext
    val crm = ConnReqMsgHelper.buildReqMsg(amw)
    handleConnReqMsgBase(crm, sourceId)
  }

  def handleAcceptConnReqMsg(amw: AgentMsgWrapper): PackedMsg = {
    implicit val amc: AgentMsgContext = amw.getAgentMsgContext
    val cram = AcceptConnReqMsgHelper.buildReqMsg(amw)
    handleConnReqAnswerMsgBase(cram)
  }

  def handleRedirectConnReqMsg(amw: AgentMsgWrapper): PackedMsg = {
    implicit val amc: AgentMsgContext = amw.getAgentMsgContext
    val rcrm = RedirectConnReqMsgHelper.buildReqMsg(amw)
    handleRedirectConnReqMsgBase(rcrm)
  }

  def handleConnReqAcceptedMsg(amw: AgentMsgWrapper): PackedMsg = {
    implicit val amc: AgentMsgContext = amw.getAgentMsgContext
    val cram = ConnReqAcceptedMsgHelper.buildReqMsg(amw)
    handleConnReqAnswerMsgBase(cram)
  }

  def handleConnReqRedirectedMsg(amw: AgentMsgWrapper): PackedMsg = {
    implicit val amc: AgentMsgContext = amw.getAgentMsgContext
    val cram = ConnReqRedirectedMsgHelper.buildReqMsg(amw)
    handleRedirectConnReqMsgBase(cram)
  }

  def handleConnReqDeclinedMsg(amw: AgentMsgWrapper): PackedMsg = {
    implicit val amc: AgentMsgContext = amw.getAgentMsgContext
    val cram = ConnReqDeclinedMsgHelper.buildReqMsg(amw)
    handleConnReqAnswerMsgBase(cram)
  }

  def handleDeclineConnReqMsg(amw: AgentMsgWrapper): PackedMsg = {
    implicit val amc: AgentMsgContext = amw.getAgentMsgContext
    val crdm = DeclineConnReqMsgHelper.buildReqMsg(amw)
    handleConnReqAnswerMsgBase(crdm)
  }

  lazy val inviteDetailVersion: String = "2.0"

  override def getEncryptForDID: DidStr = ctx.getState.mySelfRelDIDReq
}


/**
  * Signal
  */
case class AskPairwiseCreator(fromDID: DidStr, pairwiseDID: DidStr, endpointDetailJson: String)

/**
 * Control Messages
 */
case class GetInviteDetail_MFV_0_6(override val uid: MsgId) extends GetInviteDetail with ActorMessage {
  val msgName: MsgName = MSG_TYPE_GET_INVITE_DETAIL
  val msgFamily: MsgFamily = ConnectingMsgFamily
}
case class StartConnection(withCloudAgent: Boolean, phoneNumber: String, sourceId: String, usePubDID: Boolean)
case class AcceptConnection(bobsSourceId: String)
