package com.evernym.verity.testkit.agentmsg

import com.evernym.verity.util2.Status.FORBIDDEN
import com.evernym.verity.Version
import com.evernym.verity.actor.agent.{DidPair, MsgPackFormat, Thread}
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.actor.wallet.{PackedMsg, StoreTheirKey, TheirKeyStored}
import com.evernym.verity.agentmsg._
import com.evernym.verity.agentmsg.dead_drop.GetDeadDropMsg
import com.evernym.verity.agentmsg.issuer_setup.{CreateDIDMsg, CurrentIdentifierMsg}
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily.TypeDetail
import com.evernym.verity.agentmsg.msgfamily.pairwise._
import com.evernym.verity.agentmsg.msgpacker._
import com.evernym.verity.agentmsg.question_answer.AskQuestionMsg
import com.evernym.verity.agentmsg.wallet_backup.WalletBackupMsg
import com.evernym.verity.http.common.StatusDetailResp
import com.evernym.verity.protocol.engine.Constants.{MFV_0_1_0, MFV_0_6, MSG_FAMILY_AGENT_PROVISIONING, MSG_TYPE_CONNECT}
import com.evernym.verity.protocol.engine.MsgFamily.{EVERNYM_QUALIFIER, typeStrFromMsgType}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.connecting.common.InviteDetail
import com.evernym.verity.protocol.protocols.deaddrop.{DeadDropData, DeadDropRetrieveResult}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.QuestionAnswerVars.testQuestion
import com.evernym.verity.protocol.protocols.walletBackup.WalletBackupMsgFamily.Restored
import com.evernym.verity.testkit.{AwaitResult, Matchers}
import com.evernym.verity.testkit.agentmsg.indy_pack.v_0_1.{AgentMsgBuilder => AgentMsgBuilder_v_0_1, AgentMsgHandler => AgentMsgHandler_v_0_1}
import com.evernym.verity.testkit.agentmsg.indy_pack.v_0_6.{AgentMsgBuilder => AgentMsgBuilder_v_0_6, AgentMsgHandler => AgentMsgHandler_v_0_6}
import com.evernym.verity.testkit.agentmsg.indy_pack.v_0_7.{AgentMsgBuilder => AgentMsgBuilder_v_0_7, AgentMsgHandler => AgentMsgHandler_v_0_7}
import com.evernym.verity.testkit.agentmsg.indy_pack.v_1_0.{AgentMsgBuilder => AgentMsgBuilder_v_1_0, AgentMsgHandler => AgentMsgHandler_v_1_0}
import com.evernym.verity.testkit.agentmsg.message_pack.v_0_5.{AgentMsgBuilder => AgentMsgBuilder_v_0_5, AgentMsgHandler => AgentMsgHandler_v_0_5}
import com.evernym.verity.testkit.mock.agent.{HasCloudAgent, MockAgent, MockPairwiseConnDetail}
import com.evernym.verity.testkit.util.AgentPackMsgUtil._
import com.evernym.verity.testkit.util.{AgentPackMsgUtil, _}
import com.evernym.verity.util.MsgIdProvider
import com.evernym.verity.vault._
import com.typesafe.scalalogging.Logger

import scala.reflect.ClassTag
import scala.util.Left


object AgentMsgHelper {
  val MSG_TYPE_DETAIL_CONNECT: String = typeStrFromMsgType(EVERNYM_QUALIFIER, MSG_FAMILY_AGENT_PROVISIONING, MFV_0_6, MSG_TYPE_CONNECT)
  val MSG_TYPE_DETAIL_REDIRECT_CONN_REQ: String = typeStrFromMsgType(EVERNYM_QUALIFIER, MSG_FAMILY_CONNECTING, MFV_0_6, MSG_TYPE_REDIRECT_CONN_REQ)
  val MSG_TYPE_DETAIL_DECLINE_CONN_REQ: String = typeStrFromMsgType(EVERNYM_QUALIFIER, MSG_FAMILY_CONNECTING, MFV_0_6, MSG_TYPE_DECLINE_CONN_REQ)
  val MSG_TYPE_DETAIL_WALLET_BACKUP_RESTORE: String = typeStrFromMsgType(EVERNYM_QUALIFIER, MSG_FAMILY_WALLET_BACKUP, MFV_0_1_0, MSG_TYPE_WALLET_BACKUP_RESTORE)
}

/**
 * helper methods to prepare request messages
 * and to handle response messages
 */

trait AgentMsgHelper
  extends CommonSpecUtil
    with AgentMsgBuilder_v_0_1 with AgentMsgHandler_v_0_1
    with AgentMsgBuilder_v_0_5 with AgentMsgHandler_v_0_5
    with AgentMsgBuilder_v_0_6 with AgentMsgHandler_v_0_6
    with AgentMsgBuilder_v_0_7 with AgentMsgHandler_v_0_7
    with AgentMsgBuilder_v_1_0 with AgentMsgHandler_v_1_0
    with AwaitResult {
  this: MockAgent with HasCloudAgent with Matchers =>

  implicit lazy val agentMsgTransformer: AgentMsgTransformer = new AgentMsgTransformer(testWalletAPI)

  def getDIDDetail(ddOpt: Option[DidPair]): DidPair =
    ddOpt.getOrElse(throw new RuntimeException("no DIDDetail found"))

  def getAgentDIDDetail(ddOpt: Option[DidPair]): DidPair =
    ddOpt.getOrElse(throw new RuntimeException("no AgentDID found"))

  def agencyAgentDetailReq: AgencyPublicDid = agencyPublicDid.getOrElse(throw new RuntimeException("no agency detail found"))

  def agencyPairwiseAgentDetailReq: DidPair = getDIDDetail(agencyPairwiseAgentDetail)

  def cloudAgentDetailReq: DidPair = getAgentDIDDetail(cloudAgentDetail)

  def createNewLocalPairwiseConnDetail(name: String): MockPairwiseConnDetail = addNewLocalPairwiseKey(name)

  def setLastSentInvite(pcd: MockPairwiseConnDetail, inviteDetail: InviteDetail): Unit = {
    pcd.lastSentInvite = inviteDetail
  }

  def getDIDToUnsealAgentRespMsg: DID = myDIDDetail.did

  /**
   * unseal 'indy packed' message
   * @param rmw
   * @param unsealFromDID
   * @return
   */
  protected def unsealResp_MPV_1_0(rmw: Array[Byte], unsealFromDID: DID)
  : AgentMsgWrapper = {
    val fromKeyParam = KeyParam.fromDID(unsealFromDID)
    convertToSyncReq(agentMsgTransformer.unpackAsync(rmw, fromKeyParam))
  }

  /**
   * unpack 'indy packed' message
   * @param pmw
   * @param unsealFromDID
   * @return
   */
  protected def unpackResp_MPV_1_0(pmw: PackedMsg, unsealFromDID: DID): List[AgentMsg] = {
    val umw = unsealResp_MPV_1_0(pmw.msg, unsealFromDID)
    List(umw.headAgentMsg) ++ umw.tailAgentMsgs
  }

  def logApiCallProgressMsg(msg: String): Unit = {
    Logger("APIClientHelper").info("    " + msg)
  }

  def buildPackedMsg(ampp: PackMsgParam)(implicit mpf: MsgPackFormat):  PackedMsg = {
    preparePackedRequestForAgent(ampp)
  }

  def sealParamFromEdgeToAgency: SealParam =
    SealParam(KeyParam.fromDID(agencyAgentDetailReq.DID))

  def invalidSealParamFromEdgeToAgency: SealParam = {
    val dd = generateNewAgentDIDDetail()
    SealParam(KeyParam(Left(dd.verKey)))
  }

  def encryptParamFromEdgeToAgencyAgent: EncryptParam =
    EncryptParam(
      Set(KeyParam.fromDID(agencyAgentDetailReq.DID)),
      Option(KeyParam.fromDID(myDIDDetail.did))
    )

  def encryptParamFromEdgeToAgencyAgentPairwise: EncryptParam =
    EncryptParam(
      Set(KeyParam.fromDID(agencyPairwiseAgentDetailReq.DID)),
      Option(KeyParam.fromDID(myDIDDetail.did))
    )

  def encryptParamFromEdgeToCloudAgent: EncryptParam =
    EncryptParam(
      Set(KeyParam.fromVerKey(cloudAgentDetailReq.verKey)),
      Option(KeyParam.fromDID(myDIDDetail.did))
    )

  def encryptParamFromEdgeToCloudAgentPairwise(connId: String): EncryptParam = {
    val pcd = pairwiseConnDetail(connId)
    EncryptParam(
      Set(KeyParam.fromDID(pcd.myCloudAgentPairwiseDidPair.DID)),
      Option(KeyParam.fromDID(pcd.myPairwiseDidPair.DID))
    )
  }

  def encryptParamFromEdgeToGivenDID(forDID: DID, fromConnIdOpt: Option[String]=None): EncryptParam = {
    val fromDID = fromConnIdOpt.map(pairwiseConnDetail(_).myPairwiseDidPair.DID).getOrElse(myDIDDetail.did)
    val receiveVk = if (forDID.equals(myDIDDetail.did)) {
      cloudAgentDetailReq.verKey
    } else { getVerKeyFromWallet(forDID) }

    EncryptParam(
      Set(KeyParam.fromVerKey(receiveVk)),
      Option(KeyParam.fromDID(fromDID))
    )
  }

  def convertTo[T: ClassTag](json: String): T = {
    DefaultMsgCodec.fromJson(json)
  }

  def handleForbiddenResp(json: String): Unit = {
    val std = convertTo[StatusDetailResp](json)
    require(std.statusCode == FORBIDDEN.statusCode)
  }

  def handleEndpointSet(json: String): Unit = {
    //???
  }

  def handleMessageTrackingStarted(json: String): Unit = {
    //???
  }

  def handleInitResp(json: String): AgencyPublicDid = {
    convertTo[AgencyPublicDid](json)
  }

  def handleFetchAgencyKey(agencyKeyIdentity: AgencyPublicDid): Unit = {
    agencyKeyIdentity.didPair.validate()
    setAgencyIdentity(agencyKeyIdentity)
  }

  def agencyAgentPairwiseRoutingDID: DID = agencyPairwiseAgentDetailReq.DID

  def cloudAgentRoutingDID: DID = cloudAgentDetailReq.DID

  def handleSetAgencyPairwiseAgentKey(DID: String, verKey: String): Unit = {
    setAgencyPairwiseAgentDetail(DID, verKey)
    testWalletAPI.executeSync[TheirKeyStored](StoreTheirKey(DID, verKey))
  }

  def handleAgentCreatedRespForAgent(pairwiseDIDPair: DidPair): Unit = {
    setCloudAgentDetail(pairwiseDIDPair)
  }

  def handleReceivedAgentMsg(rmw: Array[Byte]): AgentMsgWrapper = {
    unsealResp_MPV_1_0(rmw, getDIDToUnsealAgentRespMsg)
  }

  def setRemoteEntityPairwiseDID(connId: String, inviteDetail: InviteDetail): Unit = {
    val pcd = pairwiseConnDetail(connId)
    pcd.setTheirPairwiseDidPair(inviteDetail.senderDetail.DID, inviteDetail.senderDetail.verKey)
  }

  def prepareAskQuestionMsgForAgent(forDID: DID)
                                   (implicit msgPackagingContext: AgentMsgPackagingContext): PackedMsg = {
    val msg = AskQuestionMsg(
      MSG_TYPE_DETAIL_QUESTION_ANSWER_ASK_QUESTION,
      getNewMsgUniqueId,
      Thread(Option(MsgIdProvider.getNewMsgId)),
      forDID,
      testQuestion(None))
    prepareMsgForAgent(msg)
  }

  def prepareCreateDIDMsgForAgent(id: Option[String]=None)
                                 (implicit msgPackagingContext: AgentMsgPackagingContext): PackedMsg = {
    val msg = CreateDIDMsg(MSG_TYPE_DETAIL_ISSUER_SETUP_CREATE, id)
    prepareMsgForAgent(msg)
  }

  def prepareCurrentPublicIdentifierMsgForAgent(id: Option[String]=None)
                                               (implicit msgPackagingContext: AgentMsgPackagingContext): PackedMsg = {
    val msg = CurrentIdentifierMsg(MSG_TYPE_DETAIL_ISSUER_SETUP_CURRENT_IDENTIFIER, id)
    prepareMsgForAgent(msg)
  }

  //wallet msgs

  def buildBundledCreateGeneralMsgWithVersion(msgTypeVersion: String,
                                              includeSendMsg: Boolean = false,
                                              msgType: String,
                                              corePayloadMsg: PackedMsg,
                                              replyToMsgId: Option[String],
                                              title: Option[String] = None,
                                              detail: Option[String] = None):
  List[Any] = {
    List(
      CreateMsg_MFV_0_5(TypeDetail(MSG_TYPE_CREATE_MSG, msgTypeVersion),
        msgType, replyToMsgId = replyToMsgId, sendMsg = includeSendMsg),
      GeneralCreateMsgDetail_MFV_0_5(TypeDetail(MSG_TYPE_MSG_DETAIL, msgTypeVersion),
        corePayloadMsg.msg, title, detail)
    )
  }

  def buildCoreCreateGeneralMsgWithVersion(msgTypeVersion: String, includeSendMsg: Boolean = false, msgType: String,
                                           corePayloadMsg: PackedMsg, replyToMsgId: Option[String],
                                           title: Option[String] = None, detail: Option[String] = None)
                                          (encryptParam: EncryptParam)
                                          (implicit msgPackFormat: MsgPackFormat)
  : PackMsgParam = {
    val agentMsgs = buildBundledCreateGeneralMsgWithVersion(msgTypeVersion, includeSendMsg,
      msgType, corePayloadMsg, replyToMsgId, title, detail)
    buildAgentMsgPackParam(agentMsgs, encryptParam)
  }

  def prepareWalletBackupMsg(walletData: Any)(implicit msgPackagingContext: AgentMsgPackagingContext): PackedMsg = {
    val msg = WalletBackupMsg(MSG_TYPE_DETAIL_WALLET_BACKUP, walletData)
    prepareMsgForAgent(msg)
  }

  def buildGetPayloadMsg(ddd: DeadDropData)(implicit mpf: MsgPackFormat): PackMsgParam = {
    val msg = GetDeadDropMsg(MSG_TYPE_DETAIL_DEAD_DROP_RETRIEVE,
      ddd.recoveryVerKey, ddd.address, ddd.locator, ddd.locatorSignature)
    val encParam = EncryptParam(
      Set(KeyParam.fromDID(agencyAgentDetailReq.DID)),
      Option(KeyParam.fromVerKey(ddd.recoveryVerKey))
    )
    AgentPackMsgUtil(msg, encParam)
  }

  def prepareGetPayloadMsgForAgent(ddd: DeadDropData)(implicit msgPackagingContext: AgentMsgPackagingContext): PackedMsg = {
    implicit val mpf: MsgPackFormat = msgPackagingContext.msgPackFormat
    val agentMsgParam = buildGetPayloadMsg(ddd)
    preparePackedRequestForAgent(agentMsgParam)
  }

  def prepareGetPayloadMsgForAgency(ddd: DeadDropData)(implicit msgPackagingContext: AgentMsgPackagingContext): PackedMsg = {
    implicit val mpf: MsgPackFormat = msgPackagingContext.msgPackFormat
    val agentMsgParam = buildGetPayloadMsg(ddd)
    val fwdRoute = FwdRouteMsg(agencyAgentDetailReq.DID, Left(sealParamFromEdgeToAgency))
    preparePackedRequestForRoutes(msgPackagingContext.fwdMsgVersion, agentMsgParam, List(fwdRoute))
  }

  def unpackDeadDropLookupResult(packedMsg: PackedMsg, verKey: VerKey): DeadDropRetrieveResult = {
    unpackPackedMsg[DeadDropRetrieveResult](packedMsg, Option(verKey))
  }

  def unpackRestoredWalletMsg(packedMsg: Array[Byte]): Restored = {
    unpackAgentMsg[Restored](packedMsg)
  }

  def buildCoreUpdateComMethodMsgWithVersion(msgTypeVersion: String, cm: TestComMethod)
                                            (implicit msgPackFormat: MsgPackFormat): PackMsgParam = {
    val msgs = List(UpdateComMethod_MFV_0_5(TypeDetail(MSG_TYPE_UPDATE_COM_METHOD, msgTypeVersion), cm))
    AgentPackMsgUtil(msgs, encryptParamFromEdgeToCloudAgent)
  }

  def prepareUpdateComMethodMsgForAgentBase(msgTypeVersion: String, cm: TestComMethod)
                                       (implicit msgPackFormat: MsgPackFormat): PackedMsg = {
    preparePackedRequestForAgent(buildCoreUpdateComMethodMsgWithVersion(msgTypeVersion, cm))
  }

  def prepareGetMsgsForAgent(msgTypeVersion: String,
                             excludePayload: Option[String]=None,
                             uids: Option[List[String]]=None,
                             statusCodes: Option[List[String]]=None)
                            (implicit msgPackagingContext: AgentMsgPackagingContext): PackedMsg = {
    val msg = GetMsg_MFV_0_5(TypeDetail(MSG_TYPE_GET_MSGS, msgTypeVersion), excludePayload, uids, statusCodes)
    prepareMsgForAgent(msg)
  }

  //this msg is packed for cloud agent (user agent actor will receive it)
  protected def prepareMsgForAgent(msg: Any)(implicit msgPackagingContext: AgentMsgPackagingContext): PackedMsg = {
    implicit val mpf: MsgPackFormat = msgPackagingContext.msgPackFormat
    val agentMsgPackParam = AgentPackMsgUtil(msg, encryptParamFromEdgeToCloudAgent)
    prepareRoutedAgentMsg(agentMsgPackParam, Option(cloudAgentRoutingDID))
  }

  //this msg is packed for a connection (user agent pairwise actor will receive it)
  protected def prepareMsgForConnection(msg: Any, connId: String)
                                     (implicit msgPackagingContext: AgentMsgPackagingContext): PackedMsg = {
    implicit val mpf: MsgPackFormat = msgPackagingContext.msgPackFormat
    val agentMsgPackParam = buildAgentMsgPackParam(msg, encryptParamFromEdgeToCloudAgentPairwise(connId))
    prepareRoutedAgentMsg(agentMsgPackParam, Option(cloudAgentPairwiseDIDForConn(connId)))
  }

  //adds proper routing (optionally adds agency routing based on msgPackagingContext)
  protected def prepareRoutedAgentMsg(agentMsgPackParam: PackMsgParam, forDIDOpt: Option[DID]=None)
                                   (implicit msgPackagingContext: AgentMsgPackagingContext): PackedMsg = {
    implicit val mpf: MsgPackFormat = msgPackagingContext.msgPackFormat
    val agencyRoute = forDIDOpt match {
      case Some(forDID) if msgPackagingContext.packForAgencyRoute
                        => List(FwdRouteMsg(forDID, Left(sealParamFromEdgeToAgency)))
      case _            => List.empty
    }
    preparePackedRequestForRoutes(msgPackagingContext.fwdMsgVersion, agentMsgPackParam, agencyRoute)
  }

  protected def buildAgentMsgPackParam(msgs: Any, ep: EncryptParam)(implicit mpf: MsgPackFormat): PackMsgParam = {
    AgentPackMsgUtil(msgs, ep)
  }

  def unpackPackedMsg[T: ClassTag](packedMsg: PackedMsg, verKey: Option[VerKey]=None, up:UnpackParam = UnpackParam()): T = {
    unpackAgentMsg(packedMsg.msg, verKey, up)
  }

  def unpackAgentMsgFromConn[T: ClassTag](msg: Array[Byte], connId: String, up:UnpackParam = UnpackParam()): T = {
    val verKey = pairwiseConnDetail(connId).myPairwiseDidPair.verKey
    unpackAgentMsg(msg, Option(verKey), up)
  }

  def unpackAgentMsg[T: ClassTag](msg: Array[Byte], verKey: Option[VerKey]=None, up:UnpackParam = UnpackParam()): T = {
    val amw = unpackMsg(msg, verKey, up)
    val um = DefaultMsgCodec.fromJson(amw.headAgentMsg.msg)
    um
  }

  def unpackMsg(msg: Array[Byte], fromVerKey: Option[VerKey]=None, unpackParam:UnpackParam = UnpackParam()): AgentMsgWrapper = {
    val fromKeyParam = fromVerKey match {
      case Some(vk) => KeyParam.fromVerKey(vk)
      case None     => KeyParam.fromDID(getDIDToUnsealAgentRespMsg)
    }
    convertToSyncReq(agentMsgTransformer.unpackAsync(msg, fromKeyParam, unpackParam))
  }
}

case class GeneralMsgCreatedResp_MFV_0_5(mc: MsgCreated_MFV_0_5,
                                         ms: Option[MsgsSent_MFV_0_5])
case class InviteAcceptedResp_MFV_0_5(mc: MsgCreated_MFV_0_5,
                                      ms: Option[MsgsSent_MFV_0_5])

case class CreateInviteResp_MFV_0_5(mc: MsgCreated_MFV_0_5,
                                    md: InviteMsgDetail_MFV_0_5,
                                    ms: Option[MsgsSent_MFV_0_5])

case class AgentMsgPackagingContext(
                                     msgPackFormat: MsgPackFormat,
                                     fwdMsgVersion: Version,
                                     packForAgencyRoute: Boolean)