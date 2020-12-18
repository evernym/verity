package com.evernym.verity.testkit.agentmsg.indy_pack.v_0_6

import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.actor.agent.MsgPackFormat.{MPF_INDY_PACK, MPF_MSG_PACK}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.testkit.agentmsg.AgentMsgHelper._
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.{MSG_FAMILY_CONFIGS, MSG_FAMILY_PAIRWISE, MSG_TYPE_DETAIL_ACCEPT_CONN_REQ, MSG_TYPE_DETAIL_CONN_REQ, MSG_TYPE_DETAIL_CONN_REQ_ACCEPTED, MSG_TYPE_DETAIL_CREATE_AGENT, MSG_TYPE_DETAIL_CREATE_CONNECTION, MSG_TYPE_DETAIL_CREATE_KEY, MSG_TYPE_DETAIL_GET_MSGS_BY_CONNS, MSG_TYPE_DETAIL_SEND_REMOTE_MSG, MSG_TYPE_DETAIL_WALLET_INIT_BACKUP, MSG_TYPE_UPDATE_COM_METHOD, MSG_TYPE_UPDATE_CONN_STATUS, getNewMsgUniqueId}
import com.evernym.verity.agentmsg.msgfamily.pairwise.{ConnReqAcceptedMsg_MFV_0_6, DeclineConnReqMsg_MFV_0_6, RedirectConnReqMsg_MFV_0_6}
import com.evernym.verity.agentmsg.msgpacker.{FwdRouteMsg, PackMsgParam}
import com.evernym.verity.agentmsg.wallet_backup.{WalletBackupProvisionMsg, WalletBackupRestoreMsg}
import com.evernym.verity.protocol.engine.Constants.{MFV_0_6, MFV_1_0, MTV_1_0}
import com.evernym.verity.protocol.engine.MsgFamily.{EVERNYM_QUALIFIER, typeStrFromMsgType}
import com.evernym.verity.protocol.engine.{DID, MsgFamilyVersion, ThreadId, VerKey}
import com.evernym.verity.protocol.protocols.walletBackup
import com.evernym.verity.actor.agent.Thread
import com.evernym.verity.protocol.protocols.connecting.common.{AgentKeyDlgProof, InviteDetail}
import com.evernym.verity.protocol.protocols.walletBackup.BackupInitParams
import com.evernym.verity.testkit.agentmsg.{AgentMsgHelper, AgentMsgPackagingContext}
import com.evernym.verity.testkit.util.AgentPackMsgUtil._
import com.evernym.verity.testkit.mock.agent.MockAgent
import com.evernym.verity.testkit.util.{AcceptConnReq_MFV_0_6, AgentPackMsgUtil, ConnReq_MFV_0_6, Connect_MFV_0_6, CreateAgent_MFV_0_6, CreateConnection_MFV_0_6, CreateKey_MFV_0_6, GetMsgsByConns_MFV_0_6, SendRemoteMsg_MFV_0_6, TestComMethod, UpdateComMethod_MFV_0_6, UpdateConnStatus_MFV_0_6}
import com.evernym.verity.util.Util.logger
import com.evernym.verity.util.MsgIdProvider._
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.vault.{EncryptParam, KeyInfo}
import org.json.JSONObject

import scala.util.Left


trait AgentMsgBuilder { this: AgentMsgHelper with MockAgent with AgentMsgHelper =>

  object v_0_6_req {

    implicit val msgPackFormat: MsgPackFormat = MPF_INDY_PACK

    def prepareConnectMsgWithMissingTypeFieldForAgency(fwdMsgTypeVersion: String): PackedMsg = {
      val verKey = getVerKeyFromWallet(myDIDDetail.did)
      val agentMsg = Connect_MFV_0_6("", myDIDDetail.did, verKey)
      val agentMsgParam = AgentPackMsgUtil(agentMsg, encryptParamFromEdgeToAgencyAgent)
      val fwdRoute = FwdRouteMsg(agencyAgentDetailReq.DID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(fwdMsgTypeVersion, agentMsgParam, List(fwdRoute))
    }

    def prepareConnectMsgWithMissingTypeFieldForAgency: PackedMsg = {
      prepareConnectMsgWithMissingTypeFieldForAgency(MFV_0_6)
    }

    def prepareConnectCreateKey(fromDID: String, fromDIDVerKey: String, forDID: String): PackedMsg = {
      preparePackedRequestForAgent(buildCoreConnectCreateKeyMsgWithVersion(fromDID, fromDIDVerKey, forDID))
    }

    def prepareConnectCreateKeyForAgency(fromDID: DID, fromDIDVerKey: VerKey, forDID: DID): PackedMsg = {
      val agentPayloadMsgs = buildCoreConnectCreateKeyMsgWithVersion(fromDID, fromDIDVerKey, forDID)
      val fwdRoute = FwdRouteMsg(agencyAgentDetailReq.DID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(MFV_1_0, agentPayloadMsgs, List(fwdRoute))
    }

    def preparePairwiseCreateKey(forDID: DID, connId: String): PackedMsg = {
      preparePackedRequestForAgent(buildCorePairwiseCreateKeyMsg(connId, forDID))
    }

    def buildCreateConnectionMsg(sourceIdOpt: Option[String]=None, phoneNo: Option[String]=None): PackMsgParam = {
      val agentMsgs = CreateConnection_MFV_0_6(MSG_TYPE_DETAIL_CREATE_CONNECTION,
        sourceIdOpt.getOrElse(getNewMsgId), phoneNo)
      AgentPackMsgUtil(agentMsgs, encryptParamFromEdgeToGivenDID(cloudAgentRoutingDID))
    }

    def prepareCreateConnection(sourceIdOpt: Option[String], phoneNo: Option[String]=None): PackedMsg = {
      preparePackedRequestForAgent(buildCreateConnectionMsg(sourceIdOpt, phoneNo))
    }

    def preparePairwiseCreateKeyForAgency(forDID: DID, connId: String): PackedMsg = {
      val agentPayloadMsgs = buildCorePairwiseCreateKeyMsg(connId, forDID)
      val fwdRoute = FwdRouteMsg(cloudAgentRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(MFV_1_0, agentPayloadMsgs, List(fwdRoute))
    }

    def buildCorePairwiseCreateKeyMsg(fromConnId: String, forDID: DID): PackMsgParam = {
      val npc = createNewLocalPairwiseConnDetail(fromConnId)
      val agentMsgs = CreateKey_MFV_0_6(MSG_TYPE_DETAIL_CREATE_KEY,
        npc.myPairwiseDidPair.DID, npc.myPairwiseDidPair.verKey)
      AgentPackMsgUtil(agentMsgs, encryptParamFromEdgeToGivenDID(forDID))
    }
    def buildCoreCreateInviteMsg(forDID: DID, fromConnId: Option[String],
                                         ph: Option[String] = None,
                                         includeKeyDlgProof: Boolean = false, includeSendMsg: Boolean = false,
                                         includePublicDID: Boolean = false):
    PackMsgParam = {
      val keyDlgProof = if (includeKeyDlgProof) Option(buildAgentKeyDlgProofForConn(fromConnId.get)) else None

      val agentMsgs = ConnReq_MFV_0_6(
        MSG_TYPE_DETAIL_CONN_REQ,
        getNewMsgId, includeSendMsg, includePublicDID, `~thread` = Thread(thid=Option("1")),
        keyDlgProof, phoneNo = ph)

      AgentPackMsgUtil(agentMsgs, encryptParamFromEdgeToGivenDID(forDID, fromConnId))
    }

    def prepareCreateInvite(forDID: DID, fromConnId: Option[String],
                                    includeKeyDlgProof: Boolean = false, includeSendMsg: Boolean = false,
                                    ph: Option[String] = None, includePublicDID: Boolean = false)
    : PackedMsg = {
      preparePackedRequestForAgent(
        buildCoreCreateInviteMsg(forDID, fromConnId, ph, includeKeyDlgProof, includeSendMsg, includePublicDID))
    }

    def prepareCreateInviteForAgency(forDID: DID, fromConnId: Option[String],
                                     ph: Option[String] = None, includeKeyDlgProof: Boolean = false,
                                     includeSendMsg: Boolean = false, includePublicDID: Boolean = false)
    : PackedMsg = {
      val agentPayloadMsgs =
        buildCoreCreateInviteMsg(forDID, fromConnId, ph, includeKeyDlgProof, includeSendMsg, includePublicDID)
      val fwdRoute = FwdRouteMsg(forDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(MFV_1_0, agentPayloadMsgs, List(fwdRoute))
    }

    def prepareCreateAgentMsgForAgency(forDID: DID, fromDID: DID, fromDIDVerKey: VerKey): PackedMsg = {
      logger.debug("Prepare create agent msg for agency (MFV 0.6)")
      val agentPayloadMsgs = buildCoreCreateAgentMsg(forDID, fromDID, fromDIDVerKey)
      val fwdRoute = FwdRouteMsg(forDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(MFV_1_0, agentPayloadMsgs, List(fwdRoute))
    }

    def prepareConnectMsgWithWrongVerKeyForAgency(fwdMsgTypeVersion: String, wrongVerKey: VerKey): PackedMsg = {
      val agentMsg = Connect_MFV_0_6(MSG_TYPE_DETAIL_CONNECT, myDIDDetail.did, wrongVerKey)
      val agentPayloadMsgs = AgentPackMsgUtil(agentMsg, encryptParamFromEdgeToAgencyAgent)
      val fwdRoute = FwdRouteMsg(agencyAgentDetailReq.DID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(fwdMsgTypeVersion, agentPayloadMsgs, List(fwdRoute))
    }

    def prepareConnectMsgWithWrongVerKeyForAgency(wrongVerKey: VerKey): PackedMsg = {
      prepareConnectMsgWithWrongVerKeyForAgency(MTV_1_0, wrongVerKey)
    }

    def prepareConnectMsgWithInvalidPackedMsgForAgency(fwdMsgTypeVersion: String): PackedMsg = {
      val verKey = getVerKeyFromWallet(myDIDDetail.did)
      val agentMsg = Connect_MFV_0_6(MSG_TYPE_DETAIL_CONNECT, myDIDDetail.did, verKey)
      val agentPayloadMsgs = AgentPackMsgUtil(agentMsg, encryptParamFromEdgeToAgencyAgent)
      val fwdRoute = FwdRouteMsg(agencyAgentDetailReq.DID, Left(invalidSealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(fwdMsgTypeVersion, agentPayloadMsgs, List(fwdRoute))
    }

    def prepareConnectMsgWithInvalidPackedMsgForAgency: PackedMsg = {
      prepareConnectMsgWithInvalidPackedMsgForAgency(MFV_0_6)
    }

    def prepareConnectMsgForAgency(fwdMsgTypeVersion: String): PackedMsg = {
      val verKey = getVerKeyFromWallet(myDIDDetail.did)
      val agentMsg = Connect_MFV_0_6(MSG_TYPE_DETAIL_CONNECT, myDIDDetail.did, verKey)
      val agentPayloadMsgs = AgentPackMsgUtil(agentMsg, encryptParamFromEdgeToAgencyAgent)
      val fwdRoute = FwdRouteMsg(agencyAgentDetailReq.DID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(fwdMsgTypeVersion, agentPayloadMsgs, List(fwdRoute))
    }

    def prepareConnectMsgForAgency: PackedMsg = {
      prepareConnectMsgForAgency(MFV_0_6)
    }

    def prepareCreateAgentMsgBeforeRegisteredForAgencyWithVersion(msgTypeVersion: String,
                                                                  fromDID: DID,
                                                                  fromDIDVerKey: VerKey): PackedMsg = {
      val agentMsg = CreateAgent_MFV_0_6(
        MSG_TYPE_DETAIL_CREATE_AGENT, fromDID, fromDIDVerKey)
      val agentPayloadMsgs = AgentPackMsgUtil(agentMsg, encryptParamFromEdgeToAgencyAgentPairwise)
      val fwdRoute = FwdRouteMsg(agencyAgentPairwiseRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion, agentPayloadMsgs, List(fwdRoute))
    }

    def prepareCreateAgentMsgBeforeRegisteredForAgency(fromDID: DID, fromDIDVerKey: VerKey): PackedMsg = {
      prepareCreateAgentMsgBeforeRegisteredForAgencyWithVersion(MTV_1_0, fromDID, fromDIDVerKey)
    }

    def prepareCreateKeyMsgBeforeAgentCreatedForAgency(msgTypeVersion: String): PackedMsg = {
      val npc = addNewLocalPairwiseKey("tmp-test-1")
      val agentMsg = CreateKey_MFV_0_6(MSG_TYPE_DETAIL_CREATE_KEY,
        npc.myPairwiseDidPair.DID, npc.myPairwiseDidPair.verKey)
      val agentPayloadMsgs = AgentPackMsgUtil(agentMsg, encryptParamFromEdgeToAgencyAgent)
      val fwdRoute = FwdRouteMsg(myDIDDetail.did, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion, agentPayloadMsgs, List(fwdRoute))
    }

    def prepareCreateKeyMsgBeforeAgentCreatedForAgency: PackedMsg = {
      prepareCreateKeyMsgBeforeAgentCreatedForAgency(MFV_0_6)
    }

    def buildCoreCreateAgentMsg(forDID: DID, fromDID: DID, fromDIDVerKey: VerKey): PackMsgParam = {
      val agentMsg = CreateAgent_MFV_0_6(MSG_TYPE_DETAIL_CREATE_AGENT, fromDID, fromDIDVerKey)
      AgentPackMsgUtil(agentMsg, encryptParamFromEdgeToGivenDID(forDID))
    }

    def prepareCreateAgentMsg(forDID: DID, fromDID: DID, fromDIDVerKey: VerKey): PackedMsg = {
      preparePackedRequestForAgent(buildCoreCreateAgentMsg(forDID, fromDID, fromDIDVerKey))
    }

    def buildCoreUpdateComMethodMsgWithVersion(cm: TestComMethod, msgFamilyVersion: MsgFamilyVersion): PackMsgParam = {
      val msgTypeStr = typeStrFromMsgType(EVERNYM_QUALIFIER, MSG_FAMILY_CONFIGS, msgFamilyVersion, MSG_TYPE_UPDATE_COM_METHOD)
      val agentMsg = UpdateComMethod_MFV_0_6(msgTypeStr, cm)
      AgentPackMsgUtil(agentMsg, encryptParamFromEdgeToCloudAgent)
    }

    def prepareUpdateComMethodMsgForAgent(cm: TestComMethod, msgFamilyVersion: MsgFamilyVersion): PackedMsg = {
      preparePackedRequestForAgent(buildCoreUpdateComMethodMsgWithVersion(cm, msgFamilyVersion))
    }

    def prepareUpdateComMethodMsgForAgent(cm: TestComMethod): PackedMsg = {
      prepareUpdateComMethodMsgForAgent(cm, MFV_0_6)
    }

    def buildCoreConnectCreateKeyMsgWithVersion(fromDID: DID, fromDIDVerKey: VerKey,
                                                        forDID: DID): PackMsgParam = {
      val agentMsg = CreateKey_MFV_0_6(MSG_TYPE_DETAIL_CREATE_KEY, fromDID, fromDIDVerKey)
      buildAgentMsgPackParam(agentMsg, encryptParamFromEdgeToGivenDID(forDID))
    }

    def prepareCreateKeyMsgForAgency(msgTypeVersion: String, connId: String): PackedMsg = {
      val fwdRoute = FwdRouteMsg(cloudAgentRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion,
        buildCorePairwiseCreateKeyMsg(connId, cloudAgentDetailReq.DID), List(fwdRoute))
    }

    def prepareCreateKeyMsgForAgency(connId: String): PackedMsg = {
      prepareCreateKeyMsgForAgency(MTV_1_0, connId)
    }

    def buildCoreAcceptInviteMsg(connId: String, includeSendMsg: Boolean = false, inviteDetail: InviteDetail): PackMsgParam = {
      val keyDlgProof = buildAgentKeyDlgProofForConn(connId)

      val connReqAnswerMsg =
        AcceptConnReq_MFV_0_6(MSG_TYPE_DETAIL_ACCEPT_CONN_REQ,
          getNewMsgId, sendMsg = includeSendMsg, keyDlgProof, inviteDetail.senderDetail, inviteDetail.senderAgencyDetail,
          inviteDetail.connReqId, Thread())
      AgentPackMsgUtil(connReqAnswerMsg, encryptParamFromEdgeToCloudAgentPairwise(connId))
    }

    def buildCoreRedirectConnReqMsg(oldConnId: String, connId: String, inviteDetail: InviteDetail): PackMsgParam = {
      case class RedirectDetail(DID: DID, verKey: VerKey, publicDID: Option[DID]=None,
                                theirDID: DID, theirVerKey: VerKey, theirPublicDID: Option[DID]=None)
      val keyDlgProof = buildAgentKeyDlgProofForConn(connId)
      val pcd = pairwiseConnDetail(oldConnId)
      val redirectDetail = RedirectDetail(pcd.myPairwiseDidPair.DID, pcd.myPairwiseDidPair.verKey, None,
        pcd.theirPairwiseDidPair.DID, pcd.theirPairwiseDidPair.verKey, None)
      val redirectJsonObject = new JSONObject(DefaultMsgCodec.toJson(redirectDetail))
      val connReqRedirectMsg = RedirectConnReqMsg_MFV_0_6(
        MSG_TYPE_DETAIL_REDIRECT_CONN_REQ, getNewMsgId,
        sendMsg = true, redirectJsonObject, inviteDetail.connReqId, inviteDetail.senderDetail,
        inviteDetail.senderAgencyDetail, keyDlgProof, Option(Thread()))
      AgentPackMsgUtil(connReqRedirectMsg, encryptParamFromEdgeToCloudAgentPairwise(connId))
    }

    def buildCoreDeclineInviteMsg(connId: String, inviteDetail: InviteDetail): PackMsgParam = {
      val keyDlgProof = buildAgentKeyDlgProofForConn(connId)

      val declineInviteMsg = DeclineConnReqMsg_MFV_0_6(
        MSG_TYPE_DETAIL_DECLINE_CONN_REQ,
        getNewMsgUniqueId,
        sendMsg = false,
        inviteDetail.senderDetail,
        inviteDetail.senderAgencyDetail,
        inviteDetail.connReqId,
        keyDlgProof
      )

      AgentPackMsgUtil(declineInviteMsg, encryptParamFromEdgeToCloudAgentPairwise(connId))
    }

    def prepareDeclineInviteMsgForAgentWithVersion(connId: String,
                                                   inviteDetail: InviteDetail): PackedMsg = {
      preparePackedRequestForAgent(buildCoreDeclineInviteMsg(connId, inviteDetail))
    }

    def prepareDeclineInviteMsgForAgent(connId: String, inviteDetail: InviteDetail): PackedMsg = {
      prepareDeclineInviteMsgForAgentWithVersion(connId, inviteDetail)
    }

    def prepareAcceptInviteMsgForAgent(connId: String, includeSendMsg: Boolean = false,
                                               inviteDetail: InviteDetail): PackedMsg = {
      prepareAcceptInviteMsgForAgentWithVersion(connId, includeSendMsg, inviteDetail)
    }

    def prepareAcceptInviteMsgForAgentWithVersion(connId: String,
                                                  includeSendMsg: Boolean = false,
                                                  inviteDetail: InviteDetail): PackedMsg = {
      preparePackedRequestForAgent(buildCoreAcceptInviteMsg(connId, includeSendMsg, inviteDetail))
    }

    def prepareAcceptConnReqMsgForAgencyWithVersion(msgTypeVersion: String,
                                                    connId: String,
                                                    includeSendMsg: Boolean = false,
                                                    inviteDetail: InviteDetail,
                                                    alreadyAccepted: Boolean): PackedMsg = {
      if (! alreadyAccepted)
        setRemoteEntityPairwiseDID(connId, inviteDetail)
      val fwdRouteForAgentPairwiseActor = FwdRouteMsg(cloudAgentPairwiseDIDForConn(connId),
        Right(encryptParamFromEdgeToCloudAgent))
      val fwdRouteForAgent = FwdRouteMsg(cloudAgentRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion,
        buildCoreAcceptInviteMsg(connId, includeSendMsg, inviteDetail),
        List(fwdRouteForAgentPairwiseActor, fwdRouteForAgent))
    }

    def prepareRedirectConnReqMsgForAgencyWithVersion(fwdMsgTypeVersion: String,
                                                              oldConnId: String,
                                                              connId: String, inviteDetail: InviteDetail): PackedMsg = {
      val fwdRouteForAgentPairwiseActor = FwdRouteMsg(cloudAgentPairwiseDIDForConn(connId),
        Right(encryptParamFromEdgeToCloudAgent))
      val fwdRouteForAgent = FwdRouteMsg(cloudAgentRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(fwdMsgTypeVersion,
        buildCoreRedirectConnReqMsg(oldConnId, connId, inviteDetail),
        List(fwdRouteForAgentPairwiseActor, fwdRouteForAgent))
    }

    def prepareAcceptConnReqMsgForAgency(connId: String, includeSendMsg: Boolean = false,
                                                 inviteDetail: InviteDetail, alreadyAccepted: Boolean): PackedMsg = {
      prepareAcceptConnReqMsgForAgencyWithVersion(MFV_1_0, connId, includeSendMsg, inviteDetail, alreadyAccepted)
    }

    def prepareRedirectConnReqMsgForAgency(oldConnId: String, connId: String, inviteDetail: InviteDetail): PackedMsg = {
      prepareRedirectConnReqMsgForAgencyWithVersion(MFV_1_0, oldConnId, connId, inviteDetail)
    }

    def buildCoreInviteAcceptedMsg(connId: String,
                                   threadId: ThreadId,
                                   includeSendMsg: Boolean = false,
                                   inviteDetail: InviteDetail,
                                   keyDlgProof: AgentKeyDlgProof): PackMsgParam = {
      val connReqAnswerMsg =
        ConnReqAcceptedMsg_MFV_0_6(MSG_TYPE_DETAIL_CONN_REQ_ACCEPTED,
          getNewMsgId, Thread(Option(threadId)),
          sendMsg = includeSendMsg, inviteDetail.senderDetail, inviteDetail.senderAgencyDetail,
          inviteDetail.connReqId, Option(keyDlgProof))

      AgentPackMsgUtil(connReqAnswerMsg, encryptParamFromEdgeToCloudAgentPairwise(connId))
    }

    def buildCoreSendGeneralMsg(includeSendMsg: Boolean = false, msgType: String,
                                          corePayloadMsg: PackedMsg, replyToMsgId: Option[String])
                                         (encryptParam: EncryptParam): PackMsgParam = {
      val agentMsg = SendRemoteMsg_MFV_0_6(MSG_TYPE_DETAIL_SEND_REMOTE_MSG, getNewMsgUniqueId,
        msgType, corePayloadMsg.msg, sendMsg = includeSendMsg, replyToMsgId = replyToMsgId)
      AgentPackMsgUtil(agentMsg, encryptParam)
    }

    def prepareCreateGeneralMsgForConn(connId: String, includeSendMsg: Boolean = false,
                                               msgType: String, corePayloadMsg: PackedMsg,
                                               replyToMsgId: Option[String]): PackedMsg = {
      preparePackedRequestForAgent(
        buildCoreSendGeneralMsg(includeSendMsg, msgType, corePayloadMsg, replyToMsgId)
        (encryptParamFromEdgeToCloudAgentPairwise(connId))
      )
    }

    def buildCoreSendRemoteMsg(id: String, sendMsg: Boolean=false, msgType: String, corePayloadMsg: PackedMsg,
                               replyToMsgId: Option[String], title: Option[String] = None,
                               detail: Option[String] = None, thread: Option[Thread]= None)
                              (encryptParam: EncryptParam): PackMsgParam = {
      val agentMsg = SendRemoteMsg_MFV_0_6(MSG_TYPE_DETAIL_SEND_REMOTE_MSG,
        id, msgType, corePayloadMsg.msg, sendMsg, thread, title, detail, replyToMsgId)
      AgentPackMsgUtil(agentMsg, encryptParam)
    }

    def prepareSendRemoteMsgForConn(connId: String, sendMsg: Boolean = false,
                                            msgType: String, corePayloadMsg: PackedMsg,
                                            replyToMsgId: Option[String],
                                            title: Option[String] = None, detail: Option[String] = None): PackedMsg = {
      preparePackedRequestForAgent(buildCoreSendRemoteMsg(getNewMsgId, sendMsg, msgType, corePayloadMsg,
        replyToMsgId, title, detail, None)(encryptParamFromEdgeToCloudAgentPairwise(connId)))
    }

    def prepareSendRemoteMsgForConnForAgencyWithVersion(fwdMsgTypeVersion: String,
                                                        connId: String,
                                                        id: String,
                                                        sendMsg: Boolean=false,
                                                        msgType: String,
                                                        corePayloadMsg: PackedMsg,
                                                        replyToMsgId: Option[String],
                                                        title: Option[String] = None,
                                                        detail: Option[String] = None)
    : PackedMsg = {
      val fwdRouteForAgentPairwiseActor = FwdRouteMsg(cloudAgentPairwiseDIDForConn(connId),
        Right(encryptParamFromEdgeToCloudAgent))
      val fwdRouteForAgent = FwdRouteMsg(cloudAgentRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(fwdMsgTypeVersion,
        buildCoreSendRemoteMsg(id, sendMsg, msgType, corePayloadMsg, replyToMsgId, title, detail)
        (encryptParamFromEdgeToCloudAgentPairwise(connId)),
        List(fwdRouteForAgentPairwiseActor, fwdRouteForAgent))
    }

    def prepareSendRemoteMsgForConnForAgency(connId: String,
                                             id: String,
                                             sendMsg: Boolean=false,
                                             msgType: String,
                                             corePayloadMsg: PackedMsg,
                                             replyToMsgId: Option[String],
                                             title: Option[String] = None,
                                             detail: Option[String] = None)
    : PackedMsg = {
      prepareSendRemoteMsgForConnForAgencyWithVersion(MTV_1_0, connId, id, sendMsg,
        msgType, corePayloadMsg, replyToMsgId, title, detail)
    }

    private def prepareWalletInitBackupMsg(params: walletBackup.BackupInitParams, wrapIntoSendMsg: Boolean=false): PackMsgParam = {
      val agentMsg = WalletBackupProvisionMsg(MSG_TYPE_DETAIL_WALLET_INIT_BACKUP, params)
      val pmp = AgentPackMsgUtil(agentMsg, encryptParamFromEdgeToCloudAgent)
      if (wrapIntoSendMsg) {
        val pm = preparePackedRequestForAgent(pmp)
        //NOTE: this is legacy create/send message which is supposed to be using MESSAGE PACK (and not INDY_PACK)
        //thats why in below call, the last parameter passed is MESSAGE PACK
        buildCoreCreateGeneralMsgWithVersion(MTV_1_0, msgType = "test", corePayloadMsg = pm,
          replyToMsgId = None)(encryptParamFromEdgeToCloudAgent)(MPF_MSG_PACK)
      } else {
        pmp
      }
    }

    def prepareWalletInitBackupMsgForAgent(params: BackupInitParams, wrapIntoSendMsg: Boolean=false): PackedMsg = {
      val pmp = prepareWalletInitBackupMsg(params, wrapIntoSendMsg)
      preparePackedRequestForAgent(pmp)
    }

    def prepareWalletBackupInitMsgForAgency(params: BackupInitParams): PackedMsg = {
      val tamp = prepareWalletInitBackupMsg(params)
      val fwdRouteForAgent = FwdRouteMsg(cloudAgentRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(MFV_1_0, tamp, List(fwdRouteForAgent))
    }

    def prepareWalletBackupRestoreMsg(backupInitParams: BackupInitParams, encryptForVerKey: VerKey): PackMsgParam = {
      val agentMsg = WalletBackupRestoreMsg(MSG_TYPE_DETAIL_WALLET_BACKUP_RESTORE)

      val fromKeyInfo = KeyInfo(Left(backupInitParams.recoveryVk))
      val forKeyInfo = KeyInfo(Left(encryptForVerKey))

      val encryptParam = EncryptParam(Set(forKeyInfo), Option(fromKeyInfo))
      AgentPackMsgUtil(agentMsg, encryptParam)
    }

    def prepareWalletBackupRestoreMsgForAgent(backupInitParams: BackupInitParams, encryptForVerKey: VerKey)
                                             : PackedMsg = {
      preparePackedRequestForAgent(prepareWalletBackupRestoreMsg(backupInitParams, encryptForVerKey))
    }

    def prepareWalletBackupRestoreMsgForAgency(backupInitParams: BackupInitParams, encryptForVerKey: VerKey)
                                              : PackedMsg = {
      val tamp = prepareWalletBackupRestoreMsg(backupInitParams, encryptForVerKey)
      val fwdRouteForAgent = FwdRouteMsg(cloudAgentRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(MFV_1_0, tamp, List(fwdRouteForAgent))
    }

    def prepareUpdateConnStatus(status: String, connId: String)
                               (implicit msgPackagingContext: AgentMsgPackagingContext): PackedMsg = {
      val msgType = typeStrFromMsgType(EVERNYM_QUALIFIER, MSG_FAMILY_PAIRWISE, MFV_0_6, MSG_TYPE_UPDATE_CONN_STATUS)
      val msg = UpdateConnStatus_MFV_0_6(msgType, status)
      prepareMsgForConnection(msg, connId)
    }

    def prepareGetMsgsFromConns(
                                 pairwiseDIDs: Option[List[String]] = None,
                                 excludePayload: Option[String]=None,
                                 uids: Option[List[String]]=None,
                                 statusCodes: Option[List[String]]=None)
                               (implicit msgPackagingContext: AgentMsgPackagingContext)
    : PackedMsg = {
      val msg = GetMsgsByConns_MFV_0_6(MSG_TYPE_DETAIL_GET_MSGS_BY_CONNS,
        pairwiseDIDs, excludePayload, uids, statusCodes)
      prepareMsgForAgent(msg)
    }

  }
}