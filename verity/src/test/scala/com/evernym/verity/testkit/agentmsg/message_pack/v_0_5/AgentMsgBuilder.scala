package com.evernym.verity.testkit.agentmsg.message_pack.v_0_5

import com.evernym.verity.Status.MSG_STATUS_ACCEPTED
import com.evernym.verity.Version
import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_MSG_PACK
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.{CREATE_MSG_TYPE_CONN_REQ, CREATE_MSG_TYPE_CONN_REQ_ANSWER, CREATE_MSG_TYPE_REDIRECT_CONN_REQ, MSG_TYPE_CREATE_KEY, MSG_TYPE_CREATE_MSG, MSG_TYPE_GET_CONFIGS, MSG_TYPE_GET_MSGS, MSG_TYPE_GET_MSGS_BY_CONNS, MSG_TYPE_MSG_DETAIL, MSG_TYPE_REMOVE_CONFIGS, MSG_TYPE_SEND_MSGS, MSG_TYPE_UPDATE_CONFIGS, MSG_TYPE_UPDATE_CONN_STATUS, MSG_TYPE_UPDATE_MSG_STATUS, MSG_TYPE_UPDATE_MSG_STATUS_BY_CONNS}
import com.evernym.verity.agentmsg.msgfamily.pairwise.{AnswerInviteMsgDetail_MFV_0_5, InviteCreateMsgDetail_MFV_0_5, PairwiseMsgUids, RedirectConnReqMsgDetail_MFV_0_5}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.TypeDetail
import com.evernym.verity.agentmsg.msgpacker.{FwdRouteMsg, PackMsgParam}
import com.evernym.verity.protocol.engine.Constants.{MFV_1_0, MSG_TYPE_CONNECT, MSG_TYPE_CREATE_AGENT, MSG_TYPE_SIGN_UP, MTV_1_0}
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.protocol.protocols.connecting.common.{InviteDetail, SenderAgencyDetail, SenderDetail}
import com.evernym.verity.testkit.agentmsg.{AgentMsgHelper, AgentMsgPackagingContext}
import com.evernym.verity.testkit.mock.HasCloudAgent
import com.evernym.verity.testkit.mock.agent.MockAgent
import com.evernym.verity.testkit.mock.cloud_agent.MockCloudAgentBase
import com.evernym.verity.testkit.mock.edge_agent.{MockConsumerEdgeAgent, MockEntEdgeAgent}
import com.evernym.verity.testkit.util.AgentPackMsgUtil._
import com.evernym.verity.testkit.util.{AgentPackMsgUtil, Connect_MFV_0_5, CreateAgent_MFV_0_5, CreateKey_MFV_0_5, CreateMsg_MFV_0_5, GetConfigs_MFV_0_5, GetMsg_MFV_0_5, GetMsgsByConns_MFV_0_5, RemoveConfigs_MFV_0_5, SendMsgs_MFV_0_5, SignUp_MFV_0_5, TestComMethod, TestConfigDetail, UpdateConfigs_MFV_0_5, UpdateConnStatus_MFV_0_5, UpdateMsgStatusByConns_MFV_0_5, UpdateMsgStatus_MFV_0_5}
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.vault.{EncryptParam, GetVerKeyByDIDParam, KeyParam, SealParam}
import org.json.JSONObject

import scala.util.Left

/**
 * this is helper class containing different agent message builder methods
 */
trait AgentMsgBuilder { this: AgentMsgHelper with MockAgent with HasCloudAgent =>

  object v_0_5_req {

    implicit val msgPackFormat: MsgPackFormat = MPF_MSG_PACK

    def buildCoreSignUpMsgWithVersion(msgTypeVersion: String): PackMsgParam = {
      val agentMsg = SignUp_MFV_0_5(TypeDetail(MSG_TYPE_SIGN_UP, msgTypeVersion))
      buildAgentMsgPackParam(agentMsg, encryptParamFromEdgeToAgencyAgent)
    }

    def prepareUnsupportedMsgForAgencyWithVersion(msgTypeVersion: String): PackedMsg = {
      val agentMsg = SignUp_MFV_0_5(TypeDetail(MSG_TYPE_SIGN_UP, msgTypeVersion))
      val payload = buildAgentMsgPackParam(agentMsg, encryptParamFromEdgeToAgencyAgent)
      preparePackedRequestForAgent(payload)
    }

    def prepareUnsupportedMsgWithMoreThanOneMsgsForAgency(msgTypeVersion: String): PackedMsg = {
      val agentMsgs = List(
        SignUp_MFV_0_5(TypeDetail(MSG_TYPE_SIGN_UP, msgTypeVersion)),
        SignUp_MFV_0_5(TypeDetail(MSG_TYPE_CONNECT, msgTypeVersion))
      )
      val payload = buildAgentMsgPackParam(agentMsgs, encryptParamFromEdgeToAgencyAgent)
      preparePackedRequestForAgent(payload)
    }

    private def buildCoreConnectMsgWithVersion(version: String, useRandomDetails: Boolean = false): PackMsgParam = {
      val (did, verKey) = if (useRandomDetails) {
        val dd = CommonSpecUtil.generateNewAgentDIDDetail()
        (dd.did, dd.verKey)
      } else {
        (myDIDDetail.did, getVerKeyFromWallet(myDIDDetail.did))
      }
      val agentMsg = Connect_MFV_0_5(TypeDetail(MSG_TYPE_CONNECT, version), did, verKey)
      buildAgentMsgPackParam(agentMsg, encryptParamFromEdgeToAgencyAgent)
    }

    def prepareConnectMsg(useRandomDetails: Boolean = false): PackedMsg = {
      preparePackedRequestForAgent(buildCoreConnectMsgWithVersion(MTV_1_0, useRandomDetails))
    }

    def buildSignUpMsgForAgency(msgTypeVersion: String): PackedMsg = {
      preparePackedRequestForAgent(buildCoreSignUpMsgWithVersion(msgTypeVersion))
    }

    def prepareSignUpMsgBeforeConnectedForAgency(msgTypeVersion: String): PackedMsg = {
      val agentMsg = SignUp_MFV_0_5(TypeDetail(MSG_TYPE_SIGN_UP, msgTypeVersion))
      val agentMsgParam = buildAgentMsgPackParam(agentMsg, encryptParamFromEdgeToAgencyAgent)
      val fwdRoute = FwdRouteMsg(myDIDDetail.did, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion, agentMsgParam, List(fwdRoute))
    }

    def prepareSignUpMsgBeforeConnectedForAgency: PackedMsg = {
      prepareSignUpMsgBeforeConnectedForAgency(MTV_1_0)
    }

    def prepareConnectMsgWithMissingTypeFieldForAgency(msgTypeVersion: String): PackedMsg = {
      val verKey = getVerKeyFromWallet(myDIDDetail.did)
      val agentMsg = Connect_MFV_0_5(TypeDetail("", ""), myDIDDetail.did, verKey)
      val agentMsgParam = buildAgentMsgPackParam(agentMsg, encryptParamFromEdgeToAgencyAgent)
      val fwdRoute = FwdRouteMsg(agencyAgentDetailReq.DID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion, agentMsgParam, List(fwdRoute))
    }

    def prepareConnectMsgWithMissingTypeFieldForAgency: PackedMsg = {
      prepareConnectMsgWithMissingTypeFieldForAgency(MTV_1_0)
    }

    def prepareSignUpMsgForVersion(msgTypeVersion: String): PackedMsg = {
      val agentMsgs = SignUp_MFV_0_5(TypeDetail(MSG_TYPE_SIGN_UP, msgTypeVersion))
      val agentPayloadMsgs = buildAgentMsgPackParam(agentMsgs, encryptParamFromEdgeToAgencyAgentPairwise)
      preparePackedRequestForAgent(agentPayloadMsgs)
    }

    def prepareSignUpMsgWithVersion(msgTypeVersion: String): PackedMsg = {
      prepareSignUpMsgForVersion(msgTypeVersion)
    }

    def prepareSignUpMsg: PackedMsg = {
      prepareSignUpMsgWithVersion(MTV_1_0)
    }

    def prepareInvalidPackedConnectMsgForAgency(msgTypeVersion: String): PackedMsg = {
      val verKey = getVerKeyFromWallet(myDIDDetail.did)
      val msgs = List(Connect_MFV_0_5(TypeDetail(MSG_TYPE_CONNECT, msgTypeVersion), myDIDDetail.did, verKey))
      val agentPayloadMsgs = AgentPackMsgUtil(msgs, encryptParamFromEdgeToAgencyAgent)
      val fwdRoute = FwdRouteMsg(agencyAgentDetailReq.DID, Left(invalidSealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion, agentPayloadMsgs, List(fwdRoute))
    }

    def prepareInvalidPackedConnectMsgForAgency: PackedMsg = {
      prepareInvalidPackedConnectMsgForAgency(MTV_1_0)
    }

    def prepareConnectMsgWithWrongVerKeyForAgency(fwdMsgTypeVersion: String, wrongVerKey: VerKey): PackedMsg = {
      val agentMsg = List(Connect_MFV_0_5(
          TypeDetail(MSG_TYPE_CONNECT, fwdMsgTypeVersion), myDIDDetail.did, wrongVerKey))
      val agentPayloadMsgs = AgentPackMsgUtil(agentMsg, encryptParamFromEdgeToAgencyAgent)
      val fwdRoute = FwdRouteMsg(agencyAgentDetailReq.DID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(fwdMsgTypeVersion, agentPayloadMsgs, List(fwdRoute))
    }

    def prepareConnectMsgWithWrongVerKeyForAgency(wrongVerKey: VerKey): PackedMsg = {
      prepareConnectMsgWithWrongVerKeyForAgency(MTV_1_0, wrongVerKey)
    }

    def prepareConnectMsgForAgency(fwdMsgTypeVersion: String): PackedMsg = {
      val verKey = getVerKeyFromWallet(myDIDDetail.did)
      val agentMsg = Connect_MFV_0_5(TypeDetail(MSG_TYPE_CONNECT, fwdMsgTypeVersion), myDIDDetail.did, verKey)
      val agentPayloadMsgs = buildAgentMsgPackParam(agentMsg, encryptParamFromEdgeToAgencyAgent)
      val fwdRoute = FwdRouteMsg(agencyAgentDetailReq.DID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(fwdMsgTypeVersion, agentPayloadMsgs, List(fwdRoute))
    }

    def prepareConnectMsgForAgency: PackedMsg = {
      prepareConnectMsgForAgency(MTV_1_0)
    }

    def prepareCreateAgentMsgBeforeRegisteredForAgency(msgTypeVersion: String): PackedMsg = {
      val bundledMsgs = List(CreateAgent_MFV_0_5(
        TypeDetail(MSG_TYPE_CREATE_AGENT, msgTypeVersion)))
      val agentPayloadMsgs = AgentPackMsgUtil(bundledMsgs, encryptParamFromEdgeToAgencyAgentPairwise)
      val fwdRoute = FwdRouteMsg(agencyAgentPairwiseRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion, agentPayloadMsgs, List(fwdRoute))
    }

    def prepareCreateAgentMsgBeforeRegisteredForAgency: PackedMsg = {
      prepareCreateAgentMsgBeforeRegisteredForAgency(MTV_1_0)
    }

    def prepareSignUpMsgForAgency(msgTypeVersion: String): PackedMsg = {
      val bundledMsgs = List(SignUp_MFV_0_5(TypeDetail(MSG_TYPE_SIGN_UP, msgTypeVersion)))
      val agentPayloadMsgs = AgentPackMsgUtil(bundledMsgs, encryptParamFromEdgeToAgencyAgentPairwise)
      val fwdRoute = FwdRouteMsg(agencyAgentPairwiseRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion, agentPayloadMsgs, List(fwdRoute))
    }

    def prepareSignUpMsgForAgency: PackedMsg = {
      prepareSignUpMsgForAgency(MTV_1_0)
    }

    def prepareCreateKeyMsgBeforeAgentCreatedForAgency(msgTypeVersion: String): PackedMsg = {
      val npc = addNewLocalPairwiseKey("tmp-test-1")
      val bundledMsgs = List(CreateKey_MFV_0_5(TypeDetail(MSG_TYPE_CREATE_KEY, msgTypeVersion),
        npc.myPairwiseDidPair.DID, npc.myPairwiseDidPair.verKey))
      val agentPayloadMsgs = AgentPackMsgUtil(bundledMsgs, encryptParamFromEdgeToAgencyAgent)
      val fwdRoute = FwdRouteMsg(myDIDDetail.did, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion, agentPayloadMsgs, List(fwdRoute))
    }

    def prepareCreateKeyMsgBeforeAgentCreatedForAgency: PackedMsg = {
      prepareCreateKeyMsgBeforeAgentCreatedForAgency(MTV_1_0)
    }

    def buildCoreCreateAgentMsgWithVersion(msgTypeVersion: String): PackMsgParam = {
      val msgs = List(CreateAgent_MFV_0_5(TypeDetail(MSG_TYPE_CREATE_AGENT, msgTypeVersion)))
      AgentPackMsgUtil(msgs, encryptParamFromEdgeToAgencyAgentPairwise)
    }

    def prepareCreateAgentMsg(msgTypeVersion: String): PackedMsg = {
      preparePackedRequestForAgent(buildCoreCreateAgentMsgWithVersion(msgTypeVersion))
    }

    def prepareCreateAgentMsg: PackedMsg = {
      prepareCreateAgentMsg(MTV_1_0)
    }

    def prepareCreateAgentMsgForAgency(msgTypeVersion: String): PackedMsg = {
      val bundledMsgs = List(CreateAgent_MFV_0_5(TypeDetail(MSG_TYPE_CREATE_AGENT, msgTypeVersion)))
      val agentPayloadMsgs = AgentPackMsgUtil(bundledMsgs, encryptParamFromEdgeToAgencyAgentPairwise)
      val fwdRoute = FwdRouteMsg(agencyAgentPairwiseRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion, agentPayloadMsgs, List(fwdRoute))
    }

    def prepareCreateAgentMsgForAgency: PackedMsg = {
      prepareCreateAgentMsgForAgency(MTV_1_0)
    }

    def prepareUpdateConfigsForAgentMsgForAgency(msgTypeVersion: String, configs: Set[TestConfigDetail]): PackedMsg = {
      val bundledMsgs = List(UpdateConfigs_MFV_0_5(
        TypeDetail(MSG_TYPE_UPDATE_CONFIGS, msgTypeVersion), configs))
      val agentPayloadMsgs = AgentPackMsgUtil(bundledMsgs, encryptParamFromEdgeToCloudAgent)
      val fwdRoute = FwdRouteMsg(cloudAgentRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion, agentPayloadMsgs, List(fwdRoute))
    }

    def prepareUpdateConfigsForAgentMsgForAgency(configs: Set[TestConfigDetail]): PackedMsg = {
      prepareUpdateConfigsForAgentMsgForAgency(MTV_1_0, configs)
    }

    def prepareUpdateConfigsForConnMsgForAgency(msgTypeVersion: String, connId: String, configs: Set[TestConfigDetail]): PackedMsg = {
      val bundledMsgs = List(UpdateConfigs_MFV_0_5(TypeDetail(MSG_TYPE_UPDATE_CONFIGS, msgTypeVersion), configs))
      val agentPayloadMsgs = AgentPackMsgUtil(bundledMsgs, encryptParamFromEdgeToCloudAgentPairwise(connId))
      val fwdRouteForAgentPairwiseActor = FwdRouteMsg(cloudAgentPairwiseDIDForConn(connId),
        Right(encryptParamFromEdgeToCloudAgent))
      val fwdRouteForAgent = FwdRouteMsg(cloudAgentRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion, agentPayloadMsgs, List(fwdRouteForAgentPairwiseActor,
        fwdRouteForAgent))
    }

    def prepareUpdateConfigsForConnMsgForAgency(connId: String, configs: Set[TestConfigDetail]): PackedMsg = {
      prepareUpdateConfigsForConnMsgForAgency(MTV_1_0, connId, configs)
    }

    def prepareRemoveConfigsForAgentWithVersion(msgTypeVersion: String, configs: Set[String]): PackedMsg = {
      val bundledMsgs = List(RemoveConfigs_MFV_0_5(
        TypeDetail(MSG_TYPE_REMOVE_CONFIGS, msgTypeVersion), configs))
      val agentPayloadMsgs = AgentPackMsgUtil(bundledMsgs, encryptParamFromEdgeToCloudAgent)
      preparePackedRequestForAgent(agentPayloadMsgs)
    }

    def prepareRemoveConfigsForAgent(msgTypeVersion: String, configs: Set[String]): PackedMsg = {
      prepareRemoveConfigsForAgentWithVersion(msgTypeVersion, configs)
    }

    def prepareRemoveConfigsForAgent(configs: Set[String]): PackedMsg = {
      prepareRemoveConfigsForAgent(MTV_1_0, configs)
    }

    def prepareUpdateConfigsForAgentWithVersion(msgTypeVersion: String, configs: Set[TestConfigDetail]): PackedMsg = {
      val bundledMsgs = List(UpdateConfigs_MFV_0_5(TypeDetail(MSG_TYPE_UPDATE_CONFIGS, msgTypeVersion), configs))
      val agentPayloadMsgs = AgentPackMsgUtil(bundledMsgs, encryptParamFromEdgeToCloudAgent)
      preparePackedRequestForAgent(agentPayloadMsgs)
    }

    def prepareUpdateConfigsForAgent(msgTypeVersion: String, configs: Set[TestConfigDetail]): PackedMsg = {
      prepareUpdateConfigsForAgentWithVersion(msgTypeVersion, configs)
    }

    def prepareUpdateConfigsForAgent(configs: Set[TestConfigDetail]): PackedMsg = {
      prepareUpdateConfigsForAgentWithVersion(MTV_1_0, configs)
    }

    def prepareUpdateComMethodMsgForAgent(cm: TestComMethod): PackedMsg = {
      prepareUpdateComMethodMsgForAgentBase(MTV_1_0, cm)
    }

    def prepareUpdateComMethodMsgForAgency(msgTypeVersion: String, cm: TestComMethod): PackedMsg = {
      val fwdRouteForAgent = FwdRouteMsg(cloudAgentRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion, buildCoreUpdateComMethodMsgWithVersion(msgTypeVersion, cm),
        List(fwdRouteForAgent))
    }

    def prepareUpdateComMethodMsgForAgency(cm: TestComMethod): PackedMsg = {
      prepareUpdateComMethodMsgForAgency(MTV_1_0, cm)
    }

    def prepareRemoveConfigsMsgForConnForAgency(msgTypeVersion: String, connId: String,
                                                configs: Set[String]): PackedMsg = {
      val bundledMsgs = List(RemoveConfigs_MFV_0_5(
        TypeDetail(MSG_TYPE_REMOVE_CONFIGS, msgTypeVersion), configs))
      val agentPayloadMsgs = AgentPackMsgUtil(bundledMsgs, encryptParamFromEdgeToCloudAgentPairwise(connId))
      val fwdRouteForAgentPairwiseActor = FwdRouteMsg(cloudAgentPairwiseDIDForConn(connId),
        Right(encryptParamFromEdgeToCloudAgent))
      val fwdRouteForAgent = FwdRouteMsg(cloudAgentRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion, agentPayloadMsgs, List(fwdRouteForAgentPairwiseActor,
        fwdRouteForAgent))
    }

    def prepareRemoveConfigsMsgForConnForAgency(connId: String,
                                                        configs: Set[String]): PackedMsg = {
      prepareRemoveConfigsMsgForConnForAgency(MTV_1_0, connId, configs)
    }

    def buildCoreGetAgentConfigsMsgWithVersion(msgTypeVersion: String, configs: Set[String]): PackMsgParam = {
      val bundledMsgs = List(GetConfigs_MFV_0_5(TypeDetail(
        MSG_TYPE_GET_CONFIGS, msgTypeVersion), configs))
      AgentPackMsgUtil(bundledMsgs, encryptParamFromEdgeToCloudAgent)
    }

    def prepareGetAgentConfigsMsgForAgent(msgTypeVersion: String, configs: Set[String]): PackedMsg = {
      preparePackedRequestForAgent(buildCoreGetAgentConfigsMsgWithVersion(msgTypeVersion,
        configs))
    }

    def prepareGetAgentConfigsMsgForAgent(configs: Set[String]): PackedMsg = {
      prepareGetAgentConfigsMsgForAgent(MTV_1_0, configs)
    }

    def prepareGetAgentConfigsMsgForAgency(msgTypeVersion: String, configs: Set[String]): PackedMsg = {
      val bundledMsgs = List(GetConfigs_MFV_0_5(
        TypeDetail(MSG_TYPE_GET_CONFIGS, msgTypeVersion), configs))
      val agentPayloadMsgs = AgentPackMsgUtil(bundledMsgs, encryptParamFromEdgeToCloudAgent)
      val fwdRoute = FwdRouteMsg(cloudAgentRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion, agentPayloadMsgs, List(fwdRoute))
    }

    def prepareGetAgentConfigsMsgForAgency(configs: Set[String]): PackedMsg = {
      prepareGetAgentConfigsMsgForAgency(MTV_1_0, configs)
    }

    def buildCoreCreateKeyMsgWithVersion(msgTypeVersion: String, connId: String): PackMsgParam = {
      val npc = createNewLocalPairwiseConnDetail(connId)
      val bundledMsgs = List(CreateKey_MFV_0_5(TypeDetail(MSG_TYPE_CREATE_KEY, msgTypeVersion),
        npc.myPairwiseDidPair.DID, npc.myPairwiseDidPair.verKey))
      AgentPackMsgUtil(bundledMsgs, encryptParamFromEdgeToCloudAgent)
    }

    def prepareCreateKeyMsgForAgent(msgTypeVersion: String, connId: String): PackedMsg = {
      preparePackedRequestForAgent(buildCoreCreateKeyMsgWithVersion(msgTypeVersion, connId))
    }

    def prepareCreateKeyMsgForAgent(connId: String): PackedMsg = {
      prepareCreateKeyMsgForAgent(MTV_1_0, connId)
    }

    def prepareCreateKeyMsgForAgency(msgTypeVersion: String, connId: String): PackedMsg = {
      val fwdRoute = FwdRouteMsg(cloudAgentRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion, buildCoreCreateKeyMsgWithVersion(msgTypeVersion, connId),
        List(fwdRoute))
    }

    def prepareCreateKeyMsgForAgency(connId: String): PackedMsg = {
      prepareCreateKeyMsgForAgency(MTV_1_0, connId)
    }

    def prepareCreateInviteMsgWithVersion(msgTypeVersion: String,
                                          connId: String, includeSendMsg: Boolean = false,
                                          includePublicDID: Boolean = false,
                                          ph: Option[String] = None): PackedMsg = {
      preparePackedRequestForAgent(
        buildCoreCreateInviteMsgWithVersion(msgTypeVersion, connId, ph, includeSendMsg, includePublicDID)
      )
    }

    def prepareCreateInviteMsg(connId: String, includeSendMsg: Boolean = false, includePublicDID: Boolean = false,
                                       ph: Option[String] = None): PackedMsg = {
      prepareCreateInviteMsgWithVersion(MTV_1_0, connId, includeSendMsg, includePublicDID, ph)
    }

    def prepareCreateInviteMsgForAgencyWithVersion(msgTypeVersion: String, connId: String,
                                                           ph: Option[String] = None,
                                                           includeSendMsg: Boolean = false,
                                                           includePublicDID: Boolean = false)
    : PackedMsg = {
      val fwdRouteForAgentPairwiseActor = FwdRouteMsg(cloudAgentPairwiseDIDForConn(connId),
        Right(encryptParamFromEdgeToCloudAgent))
      val fwdRouteForAgent = FwdRouteMsg(cloudAgentRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion,
        buildCoreCreateInviteMsgWithVersion(msgTypeVersion, connId, ph, includeSendMsg, includePublicDID = includePublicDID),
        List(fwdRouteForAgentPairwiseActor, fwdRouteForAgent))
    }

    def prepareCreateInviteMsgForAgency(connId: String, ph: Option[String] = None,
                                                includePublicDID: Boolean = false)
    : PackedMsg = {
      prepareCreateInviteMsgForAgencyWithVersion(MTV_1_0, connId, ph, includeSendMsg=ph.isDefined, includePublicDID)
    }

    def buildCoreCreateAnswerInviteMsg(msgTypeVersion: String, connId: String, includeSendMsg: Boolean = false,
                                               inviteDetail: InviteDetail): PackMsgParam = {
      val keyDlgProof = buildAgentKeyDlgProofForConn(connId)

      val agentMsgs = List(
        CreateMsg_MFV_0_5(TypeDetail(MSG_TYPE_CREATE_MSG, msgTypeVersion),
          CREATE_MSG_TYPE_CONN_REQ_ANSWER, None, replyToMsgId = Option(inviteDetail.connReqId), sendMsg = includeSendMsg),
        AnswerInviteMsgDetail_MFV_0_5(TypeDetail(MSG_TYPE_MSG_DETAIL, msgTypeVersion),
          inviteDetail.senderDetail, inviteDetail.senderAgencyDetail, MSG_STATUS_ACCEPTED.statusCode, Option(keyDlgProof))
      )
      buildAgentMsgPackParam(agentMsgs, encryptParamFromEdgeToCloudAgentPairwise(connId))
    }

    def buildCoreRedirectConnReqMsg(oldConnId: String, connId: String, inviteDetail: InviteDetail): PackMsgParam = {
      case class RedirectDetail(DID: DID, verKey: VerKey, publicDID: Option[DID]=None,
                                theirDID: DID, theirVerKey: VerKey, theirPublicDID: Option[DID]=None)
      val keyDlgProof = buildAgentKeyDlgProofForConn(connId)
      val pcd = pairwiseConnDetail(oldConnId)
      val redirectDetail = RedirectDetail(pcd.myPairwiseDidPair.DID, pcd.myPairwiseDidPair.verKey, None,
        pcd.theirPairwiseDidPair.DID, pcd.theirPairwiseDidPair.verKey, None)
      val redirectJsonObject = new JSONObject(DefaultMsgCodec.toJson(redirectDetail))

      val agentMsgs = List(
        CreateMsg_MFV_0_5(TypeDetail(MSG_TYPE_CREATE_MSG, MTV_1_0),
          CREATE_MSG_TYPE_REDIRECT_CONN_REQ, None, replyToMsgId = Option(inviteDetail.connReqId), sendMsg = true),
        RedirectConnReqMsgDetail_MFV_0_5(TypeDetail(MSG_TYPE_MSG_DETAIL, MTV_1_0),
          inviteDetail.senderDetail, inviteDetail.senderAgencyDetail, redirectJsonObject, Option(keyDlgProof))
      )
      buildAgentMsgPackParam(agentMsgs, encryptParamFromEdgeToCloudAgentPairwise(connId))
    }

    def prepareCreateAnswerInviteMsgForAgentWithVersion(msgTypeVersion: String, connId: String,
                                                        includeSendMsg: Boolean = false,
                                                        inviteDetail: InviteDetail): PackedMsg = {
      preparePackedRequestForAgent(buildCoreCreateAnswerInviteMsg(msgTypeVersion,
        connId, includeSendMsg, inviteDetail))
    }

    def prepareCreateAnswerInviteMsgForAgent(connId: String, includeSendMsg: Boolean = false,
                                                     inviteDetail: InviteDetail): PackedMsg = {
      prepareCreateAnswerInviteMsgForAgentWithVersion(MTV_1_0, connId, includeSendMsg, inviteDetail)
    }

    def prepareCreateAnswerInviteMsgForAgencyWithVersion(msgTypeVersion: String, connId: String, includeSendMsg: Boolean = false,
                                                                 inviteDetail: InviteDetail)
                                                                : PackedMsg = {
      val fwdRouteForAgentPairwiseActor = FwdRouteMsg(cloudAgentPairwiseDIDForConn(connId),
        Right(encryptParamFromEdgeToCloudAgent))
      val fwdRouteForAgent = FwdRouteMsg(cloudAgentRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion,
        buildCoreCreateAnswerInviteMsg(msgTypeVersion, connId, includeSendMsg, inviteDetail),
        List(fwdRouteForAgentPairwiseActor, fwdRouteForAgent))
    }

    def prepareCreateAnswerInviteMsgForAgency(connId: String, includeSendMsg: Boolean = false,
                                                      inviteDetail: InviteDetail): PackedMsg = {
      prepareCreateAnswerInviteMsgForAgencyWithVersion(MTV_1_0, connId, includeSendMsg, inviteDetail)
    }

    def prepareRedirectConnReqMsgForAgencyWithVersion(fwdMsgTypeVersion: String,
                                                              oldConnId: String,
                                                              connId: String, inviteDetail: InviteDetail)
                                                             : PackedMsg = {
      val fwdRouteForAgentPairwiseActor = FwdRouteMsg(cloudAgentPairwiseDIDForConn(connId),
        Right(encryptParamFromEdgeToCloudAgent))
      val fwdRouteForAgent = FwdRouteMsg(cloudAgentRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(fwdMsgTypeVersion,
        buildCoreRedirectConnReqMsg(oldConnId, connId, inviteDetail),
        List(fwdRouteForAgentPairwiseActor, fwdRouteForAgent))
    }

    def prepareRedirectConnReqMsgForAgency(oldConnId: String, connId: String, inviteDetail: InviteDetail): PackedMsg = {
      prepareRedirectConnReqMsgForAgencyWithVersion(MFV_1_0, oldConnId, connId, inviteDetail)
    }

    def prepareCreateAnswerInviteMsgWithoutKeyDlgProofForAgencyWithVersion(msgTypeVersion: String, connId: String,
                                                                                   includeSendMsg: Boolean = false,
                                                                                   inviteDetail: InviteDetail)
                                                                                  : PackedMsg = {
      val agentMsgs = List(
        CreateMsg_MFV_0_5(TypeDetail(MSG_TYPE_CREATE_MSG, msgTypeVersion),
          CREATE_MSG_TYPE_CONN_REQ_ANSWER,
          None, replyToMsgId = Option(inviteDetail.connReqId), sendMsg = includeSendMsg),
        AnswerInviteMsgDetail_MFV_0_5(TypeDetail(MSG_TYPE_MSG_DETAIL, msgTypeVersion),
          inviteDetail.senderDetail, inviteDetail.senderAgencyDetail, MSG_STATUS_ACCEPTED.statusCode, None)
      )
      val agentPayloadMsgs = buildAgentMsgPackParam(agentMsgs, encryptParamFromEdgeToCloudAgentPairwise(connId))
      val fwdRouteForAgentPairwiseActor = FwdRouteMsg(cloudAgentPairwiseDIDForConn(connId),
        Right(encryptParamFromEdgeToCloudAgent))
      val fwdRouteForAgent = FwdRouteMsg(cloudAgentRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion, agentPayloadMsgs, List(fwdRouteForAgentPairwiseActor,
        fwdRouteForAgent))
    }

    def prepareCreateAnswerInviteMsgWithoutKeyDlgProofForAgency(connId: String,
                                                                        includeSendMsg: Boolean = false,
                                                                        inviteDetail: InviteDetail): PackedMsg = {
      prepareCreateAnswerInviteMsgWithoutKeyDlgProofForAgencyWithVersion(MTV_1_0, connId, includeSendMsg, inviteDetail)
    }

    def prepareCreateAnswerInviteMsgWithoutReplyToMsgIdForAgencyWithVersion(msgTypeVersion: String, connId: String,
                                                                                    includeSendMsg: Boolean = false,
                                                                                    inviteDetail: InviteDetail)
                                                                                   : PackedMsg = {
      val keyDlgProof = buildAgentKeyDlgProofForConn(connId)

      val agentMsgs = List(
        CreateMsg_MFV_0_5(TypeDetail(MSG_TYPE_CREATE_MSG, msgTypeVersion),
          CREATE_MSG_TYPE_CONN_REQ_ANSWER,
          None, replyToMsgId = None, sendMsg = includeSendMsg),
        AnswerInviteMsgDetail_MFV_0_5(TypeDetail(MSG_TYPE_MSG_DETAIL, msgTypeVersion),
          inviteDetail.senderDetail, inviteDetail.senderAgencyDetail, MSG_STATUS_ACCEPTED.statusCode, Option(keyDlgProof))
      )

      val agentPayloadMsgs = buildAgentMsgPackParam(agentMsgs, encryptParamFromEdgeToCloudAgentPairwise(connId))
      val fwdRouteForAgentPairwiseActor = FwdRouteMsg(cloudAgentPairwiseDIDForConn(connId),
        Right(encryptParamFromEdgeToCloudAgent))
      val fwdRouteForAgent = FwdRouteMsg(cloudAgentRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion, agentPayloadMsgs, List(fwdRouteForAgentPairwiseActor,
        fwdRouteForAgent))
    }

    def prepareCreateAnswerInviteMsgWithoutReplyToMsgIdForAgency(connId: String,
                                                                         includeSendMsg: Boolean = false,
                                                                         inviteDetail: InviteDetail): PackedMsg = {
      prepareCreateAnswerInviteMsgWithoutReplyToMsgIdForAgencyWithVersion(MTV_1_0, connId, includeSendMsg, inviteDetail)
    }


    def buildCoreSendMsgsWithVersion(msgTypeVersion: String, connId: String, uids: List[String]): PackMsgParam = {
      val bundledMsgs = List(SendMsgs_MFV_0_5(TypeDetail(MSG_TYPE_SEND_MSGS, msgTypeVersion), uids))
      AgentPackMsgUtil(bundledMsgs, encryptParamFromEdgeToCloudAgentPairwise(connId))
    }

    def prepareSendMsgsForConnForAgency(msgTypeVersion: String, connId: String, uids: List[String])
                                               : PackedMsg = {
      preparePackedRequestForAgent(buildCoreSendMsgsWithVersion(msgTypeVersion,
        connId, uids))
    }

    def prepareSendMsgsForConnForAgency(connId: String, uids: List[String]): PackedMsg = {
      prepareSendMsgsForConnForAgency(MTV_1_0, connId, uids)
    }

    def prepareSendMsgsForAgency(msgTypeVersion: String, connId: String, uids: List[String])
                                        : PackedMsg = {
      val fwdRouteForAgentPairwiseActor = FwdRouteMsg(cloudAgentPairwiseDIDForConn(connId),
        Right(encryptParamFromEdgeToCloudAgent))
      val fwdRouteForAgent = FwdRouteMsg(cloudAgentRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion, buildCoreSendMsgsWithVersion(msgTypeVersion, connId, uids),
        List(fwdRouteForAgentPairwiseActor, fwdRouteForAgent))
    }

    def prepareSendMsgsForAgency(connId: String, uids: List[String]): PackedMsg = {
      prepareSendMsgsForAgency(MTV_1_0, connId, uids)
    }

    def prepareInviteAnsweredMsgForAgency(msgTypeVersion: String,
                                          connId: String,
                                          mockEntEdgeAgent: MockEntEdgeAgent,
                                          mockConsumerEdgeAgent: MockConsumerEdgeAgent): PackedMsg = {
      val pcd = mockEntEdgeAgent.pairwiseConnDetail(connId)

      val reqSenderKeyDlgProof = mockConsumerEdgeAgent.buildAgentKeyDlgProofForConn(connId)
      val reqSenderDetail: SenderDetail = mockConsumerEdgeAgent.buildInviteSenderDetail(connId, Option(reqSenderKeyDlgProof))
      val reqSenderAgencyDetail: SenderAgencyDetail = mockConsumerEdgeAgent.senderAgencyDetail

      val agentMsgs = List(
        CreateMsg_MFV_0_5(TypeDetail(MSG_TYPE_CREATE_MSG, msgTypeVersion),
          CREATE_MSG_TYPE_CONN_REQ_ANSWER,
          replyToMsgId = Option(pcd.lastSentInvite.connReqId)),
        AnswerInviteMsgDetail_MFV_0_5(TypeDetail(MSG_TYPE_MSG_DETAIL, msgTypeVersion),
          reqSenderDetail, reqSenderAgencyDetail, MSG_STATUS_ACCEPTED.statusCode, None)
      )

      val theirAgentEncParam = EncryptParam(
        Set(KeyParam(Right(GetVerKeyByDIDParam(pcd.lastSentInvite.senderDetail.agentKeyDlgProof.get.agentDID,
          getKeyFromPool = false)))),
        Option(KeyParam(Right(GetVerKeyByDIDParam(reqSenderKeyDlgProof.agentDID, getKeyFromPool = false))))
      )

      val agentPayloadMsgs = buildAgentMsgPackParam(agentMsgs, theirAgentEncParam)

      val remoteAgencySealInfo = SealParam(
        KeyParam(Right(GetVerKeyByDIDParam(pcd.lastSentInvite.senderAgencyDetail.DID, getKeyFromPool = false))))

      val fwdRouteForAgentPairwiseActor = FwdRouteMsg(pcd.myCloudAgentPairwiseDidPair.DID, Left(remoteAgencySealInfo))

      preparePackedRequestForRoutes(msgTypeVersion, agentPayloadMsgs, List(fwdRouteForAgentPairwiseActor))
    }

    def prepareInviteAnsweredMsgForAgency(connId: String,
                                          mockEntEdgeAgent: MockEntEdgeAgent,
                                          mockConsumerEdgeAgent: MockConsumerEdgeAgent): PackedMsg = {
      prepareInviteAnsweredMsgForAgency(MTV_1_0, connId, mockEntEdgeAgent, mockConsumerEdgeAgent)
    }

    def buildCoreCreateGeneralMsg(includeSendMsg: Boolean = false, msgType: String,
                                          corePayloadMsg: PackedMsg, replyToMsgId: Option[String],
                                          title: Option[String] = None,
                                          detail: Option[String] = None)
                                         (encryptParam: EncryptParam): PackMsgParam = {
      buildCoreCreateGeneralMsgWithVersion(MTV_1_0, includeSendMsg, msgType, corePayloadMsg, replyToMsgId, title, detail)(encryptParam)
    }

    def prepareCreateGeneralMsgForConnWithVersion(msgTypeVersion: String, connId: String, includeSendMsg: Boolean = false,
                                                          msgType: String, corePayloadMsg: PackedMsg,
                                                          replyToMsgId: Option[String])
                                                         : PackedMsg = {
      preparePackedRequestForAgent(buildCoreCreateGeneralMsgWithVersion(msgTypeVersion, includeSendMsg,
        msgType, corePayloadMsg, replyToMsgId)(encryptParamFromEdgeToCloudAgentPairwise(connId)))
    }

    def prepareCreateGeneralMsgForConn(connId: String, includeSendMsg: Boolean = false,
                                               msgType: String, corePayloadMsg: PackedMsg,
                                               replyToMsgId: Option[String]): PackedMsg = {
      prepareCreateGeneralMsgForConnWithVersion(MTV_1_0, connId, includeSendMsg, msgType, corePayloadMsg, replyToMsgId)
    }

    def prepareCreateGeneralMsgForConnForAgencyWithVersion(msgTypeVersion: String, connId: String,
                                                                   includeSendMsg: Boolean = false,
                                                                   msgType: String, corePayloadMsg: PackedMsg,
                                                                   replyToMsgId: Option[String],
                                                                   title: Option[String] = None,
                                                                   detail: Option[String] = None)
    : PackedMsg = {
      val fwdRouteForAgentPairwiseActor = FwdRouteMsg(cloudAgentPairwiseDIDForConn(connId),
        Right(encryptParamFromEdgeToCloudAgent))
      val fwdRouteForAgent = FwdRouteMsg(cloudAgentRoutingDID, Left(sealParamFromEdgeToAgency))
      preparePackedRequestForRoutes(msgTypeVersion,
        buildCoreCreateGeneralMsgWithVersion(msgTypeVersion, includeSendMsg, msgType, corePayloadMsg, replyToMsgId, title, detail)
        (encryptParamFromEdgeToCloudAgentPairwise(connId)),
        List(fwdRouteForAgentPairwiseActor, fwdRouteForAgent))
    }

    def prepareCreateGeneralMsgForConnForAgency(connId: String,
                                                includeSendMsg: Boolean = false,
                                                msgType: String, corePayloadMsg: PackedMsg,
                                                replyToMsgId: Option[String],
                                                title: Option[String] = None,
                                                detail: Option[String] = None)
    : PackedMsg = {
      prepareCreateGeneralMsgForConnForAgencyWithVersion(MTV_1_0, connId, includeSendMsg,
        msgType, corePayloadMsg, replyToMsgId, title, detail)
    }

    def prepareCreateMsgForRemoteAgencyWithVersion(msgTypeVersion: String,
                                                   connId: String,
                                                   includeSendMsg: Boolean = false,
                                                   msgType: String,
                                                   corePayloadMsg: PackedMsg,
                                                   replyToMsgId: Option[String],
                                                   title: Option[String] = None,
                                                   detail: Option[String] = None)
    : PackedMsg = {

      val remoteAgentAndAgencyIdentity = asCloudAgent.remoteAgentAndAgencyIdentityOpt.get

      val theirAgentEncParam = EncryptParam(
        Set(KeyParam(Right(GetVerKeyByDIDParam(remoteAgentAndAgencyIdentity.agentDID, getKeyFromPool = false)))),
        Option(KeyParam(Right(GetVerKeyByDIDParam(pairwiseConnDetail(connId).myPairwiseDidPair.DID, getKeyFromPool = false))))
      )

      val agentPayloadMsgs = buildCoreCreateGeneralMsgWithVersion(msgTypeVersion, includeSendMsg, msgType,
        corePayloadMsg, replyToMsgId, title, detail)(theirAgentEncParam)

      val remoteAgencySealInfo = SealParam(
        KeyParam(Right(GetVerKeyByDIDParam(remoteAgentAndAgencyIdentity.agencyDID, getKeyFromPool = false))))

      val fwdRouteForAgentPairwiseActor = FwdRouteMsg(remoteAgentAndAgencyIdentity.agentDID, Left(remoteAgencySealInfo))

      preparePackedRequestForRoutes(msgTypeVersion, agentPayloadMsgs, List(fwdRouteForAgentPairwiseActor))
    }

    def prepareCreateMsgForRemoteAgency(connId: String, includeSendMsg: Boolean = false,
                                                msgType: String, corePayloadMsg: PackedMsg,
                                                replyToMsgId: Option[String],
                                                title: Option[String] = None, detail: Option[String] = None): PackedMsg = {
      prepareCreateMsgForRemoteAgencyWithVersion(MTV_1_0, connId, includeSendMsg, msgType,
        corePayloadMsg, replyToMsgId, title, detail)
    }

    def buildCoreCreateInviteMsgWithVersion(msgTypeVersion: String, connId: String,
                                            ph: Option[String] = None,
                                            includeSendMsg: Boolean = false,
                                            includePublicDID: Boolean = false):
    PackMsgParam = {
      val keyDlgProof = buildAgentKeyDlgProofForConn(connId)

      val agentMsgs = List(
        CreateMsg_MFV_0_5(TypeDetail(MSG_TYPE_CREATE_MSG,
          msgTypeVersion), CREATE_MSG_TYPE_CONN_REQ, None, sendMsg = includeSendMsg),
        InviteCreateMsgDetail_MFV_0_5(TypeDetail(MSG_TYPE_MSG_DETAIL,
          msgTypeVersion), keyDlgProof, phoneNo = ph, includePublicDID = Option(includePublicDID))
      )

      buildAgentMsgPackParam(agentMsgs, encryptParamFromEdgeToCloudAgentPairwise(connId))
    }

    def prepareUpdateConnStatusMsg(connId: String, statusCode: String, msgTypeVersion: Version = MTV_1_0)
                                  (implicit msgPackagingContext: AgentMsgPackagingContext): PackedMsg = {
      val msg = UpdateConnStatus_MFV_0_5(
        TypeDetail(MSG_TYPE_UPDATE_CONN_STATUS, msgTypeVersion), statusCode)
      prepareMsgForConnection(msg, connId)
    }

    def prepareGetMsgsFromConn(connId: String,
                               excludePayload: Option[String]=None,
                               uids: Option[List[String]] = None,
                               statusCodes: Option[List[String]] = None,
                               msgTypeVersion: Version = MTV_1_0)
                              (implicit msgPackagingContext: AgentMsgPackagingContext)
    : PackedMsg = {
      val msg = GetMsg_MFV_0_5(TypeDetail(MSG_TYPE_GET_MSGS, msgTypeVersion),
        excludePayload, uids, statusCodes)
      prepareMsgForConnection(msg, connId)
    }

    def prepareGetMsgsFromConns(
                                 pairwiseDIDs: Option[List[String]] = None,
                                 excludePayload: Option[String]=None,
                                 uids: Option[List[String]]=None,
                                 statusCodes: Option[List[String]]=None,
                                 msgTypeVersion: Version = MTV_1_0)
                               (implicit msgPackagingContext: AgentMsgPackagingContext): PackedMsg = {
      val msg = GetMsgsByConns_MFV_0_5(TypeDetail(MSG_TYPE_GET_MSGS_BY_CONNS, msgTypeVersion),
        pairwiseDIDs, excludePayload, uids, statusCodes)
      prepareMsgForAgent(msg)
    }

    def prepareUpdateMsgStatusForConn(connId: String,
                                      uids: List[String],
                                      statusCode: String,
                                      msgTypeVersion: Version = MTV_1_0)
                                     (implicit msgPackagingContext: AgentMsgPackagingContext)
    : PackedMsg = {
      val msg = UpdateMsgStatus_MFV_0_5(TypeDetail(MSG_TYPE_UPDATE_MSG_STATUS, msgTypeVersion),
        uids, statusCode)
      prepareMsgForConnection(msg, connId)
    }

    def prepareUpdateMsgStatusByConns(uidsByConns: List[PairwiseMsgUids],
                                      statusCode: String, msgTypeVersion: Version = MTV_1_0)
                                     (implicit msgPackagingContext: AgentMsgPackagingContext)
    : PackedMsg = {
      val msg = UpdateMsgStatusByConns_MFV_0_5(
        TypeDetail(MSG_TYPE_UPDATE_MSG_STATUS_BY_CONNS, msgTypeVersion), statusCode, uidsByConns)
      prepareMsgForAgent(msg)
    }

    def prepareGetMsgs(excludePayload: Option[String]=None,
                       uids: Option[List[String]]=None,
                       statusCodes: Option[List[String]]=None,
                       msgTypeVersion: Version = MTV_1_0)
                      (implicit msgPackagingContext: AgentMsgPackagingContext)
    :PackedMsg = {
      val msg = GetMsg_MFV_0_5(TypeDetail(MSG_TYPE_GET_MSGS, msgTypeVersion),
        excludePayload, uids, statusCodes)
      prepareMsgForAgent(msg)
    }
  }

  lazy val asCloudAgent = this.asInstanceOf[MockCloudAgentBase]
}
