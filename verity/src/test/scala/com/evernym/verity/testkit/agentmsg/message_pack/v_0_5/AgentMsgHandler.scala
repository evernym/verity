package com.evernym.verity.testkit.agentmsg.message_pack.v_0_5

import com.evernym.verity.agentmsg.msgpacker.{AgentMsgWrapper, PackedMsg, UnpackParam}
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.testkit.{BasicSpecBase, Matchers}
import com.evernym.verity.testkit.agentmsg.{AgentMsgHelper, CreateInviteResp_MFV_0_5, GeneralMsgCreatedResp_MFV_0_5, InviteAcceptedResp_MFV_0_5}
import com.evernym.verity.testkit.mock.HasCloudAgent
import com.evernym.verity.testkit.mock.agent.MockAgent
import com.evernym.verity.testkit.util.{AgentCreated_MFV_0_5, ComMethodUpdated_MFV_0_5, ConfigsMsg_MFV_0_5, ConfigsRemoved_MFV_0_5, ConfigsUpdated_MFV_0_5, ConnStatusUpdated_MFV_0_5, Connected_MFV_0_5, InviteMsgDetail_MFV_0_5, KeyCreated_MFV_0_5, MsgCreated_MFV_0_5, MsgStatusUpdatedByConns_MFV_0_5, MsgStatusUpdated_MFV_0_5, MsgsByConns_MFV_0_5, MsgsSent_MFV_0_5, Msgs_MFV_0_5, SignedUp_MFV_0_5}
import com.evernym.verity.vault.{GetVerKeyByDIDParam, KeyInfo, StoreTheirKeyParam}

/**
 * this will handle received/incoming/response agent messages
 */
trait AgentMsgHandler {

  this: AgentMsgHelper with MockAgent with HasCloudAgent with Matchers =>

  object v_0_5_resp {

    def handleConnectedResp(rmw: PackedMsg, otherData: Map[String, Any]=Map.empty): Connected_MFV_0_5 = {
      val connectedMsg = unpackConnectedRespMsg(rmw, getDIDToUnsealAgentRespMsg)
      walletAPI.storeTheirKey(
        StoreTheirKeyParam(connectedMsg.withPairwiseDID, connectedMsg.withPairwiseDIDVerKey))
      setAgencyPairwiseAgentDetail(connectedMsg.withPairwiseDID, connectedMsg.withPairwiseDIDVerKey)
      connectedMsg
    }

    private def unpackConnectedRespMsg(pmw: PackedMsg, unsealFromDID: DID)
    : Connected_MFV_0_5 = {
      val cm = convertTo[Connected_MFV_0_5](unpackRespMsgs_MPV_0_5(pmw, unsealFromDID).head)
      logApiCallProgressMsg("connected: " + cm)
      cm
    }

    private def unpackSignedUpRespMsg(pmw: PackedMsg, unsealFromDID: DID)
    : SignedUp_MFV_0_5 = {
      val sm = convertTo[SignedUp_MFV_0_5](unpackRespMsgs_MPV_0_5(pmw, unsealFromDID).head)
      logApiCallProgressMsg("signed-up: " + sm)
      sm
    }

    private def unpackAgentCreatedRespMsg(pmw: PackedMsg, unsealFromDID: DID)
    : AgentCreated_MFV_0_5 = {
      val acm = convertTo[AgentCreated_MFV_0_5](unpackRespMsgs_MPV_0_5(pmw, unsealFromDID).head)
      logApiCallProgressMsg("agent-created: " + acm)
      acm
    }

    def handleSignedUpResp(rmw: PackedMsg, otherData: Map[String, Any]=Map.empty): SignedUp_MFV_0_5 = {
      unpackSignedUpRespMsg(rmw, getDIDToUnsealAgentRespMsg)
    }

    def handleAgentCreatedResp(rmw: PackedMsg, otherData: Map[String, Any]=Map.empty): AgentCreated_MFV_0_5 = {
      val acm = unpackAgentCreatedRespMsg(rmw, getDIDToUnsealAgentRespMsg)
      setCloudAgentDetail(acm.withPairwiseDID, acm.withPairwiseDIDVerKey)
      acm
    }

    def handleConfigsUpdatedResp(rmw: PackedMsg, otherData: Map[String, Any]=Map.empty): ConfigsUpdated_MFV_0_5 = {
      unpackConfigsUpdatedRespMsg(rmw, getDIDToUnsealAgentRespMsg)
    }

    def handleComMethodUpdatedResp(rmw: PackedMsg, otherData: Map[String, Any]=Map.empty): ComMethodUpdated_MFV_0_5 = {
      unpackComMethodUpdatedRespMsg(rmw, getDIDToUnsealAgentRespMsg)
    }

    def handlePairwiseAgentConfigsUpdatedResp(rmw: PackedMsg): ConfigsUpdated_MFV_0_5 = {
      unpackConfigsUpdatedRespMsg(rmw, getDIDToUnsealAgentRespMsg)
    }

    def handleConfigsRemovedResp(rmw: PackedMsg): ConfigsRemoved_MFV_0_5 = {
      unpackConfigsRemovedRespMsg(rmw, getDIDToUnsealAgentRespMsg)
    }

    def handleGetConfigsResp(rmw: PackedMsg): ConfigsMsg_MFV_0_5 = {
      unpackGetConfigRespMsg(rmw, getDIDToUnsealAgentRespMsg)
    }

    def handleKeyCreatedResp(rmw: PackedMsg, otherData: Map[String, Any]=Map.empty): KeyCreated_MFV_0_5 = {
      val pcd = pairwiseConnDetail(otherData(CONN_ID).toString)
      val kc = unpackKeyCreatedRespMsg(rmw, getDIDToUnsealAgentRespMsg)
      pcd.setMyCloudAgentPairwiseDidPair(kc.withPairwiseDID, kc.withPairwiseDIDVerKey)
      kc
    }

    def handleInviteCreatedResp(rmw: PackedMsg, otherData: Map[String, Any]=Map.empty): CreateInviteResp_MFV_0_5 = {
      val pcd = pairwiseConnDetail(otherData(CONN_ID).toString)
      val um = unpackRespMsgs_MPV_0_5(rmw, getDIDToUnsealAgentRespMsg)
      um.size shouldBe 3
      val mc = parseMsgCreatedRespMsg(um.head)
      val md = parseInviteMsgDetailRespMsg(um.tail.head)
      val ms = um.tail.tail.headOption.map(hm => parseMsgSentRespMsg(hm))
      setLastSentInvite(pcd, md.inviteDetail)
      CreateInviteResp_MFV_0_5(mc, md, ms)
    }

    def handleInviteAnswerCreatedResp(rmw: PackedMsg, otherData: Map[String, Any]=Map.empty): InviteAcceptedResp_MFV_0_5 = {
      val um = unpackRespMsgs_MPV_0_5(rmw, getDIDToUnsealAgentRespMsg)
      um.size shouldBe 2
      val mc = parseMsgCreatedRespMsg(um.head)
      val ms = um.tail.headOption.map(hm => parseMsgSentRespMsg(hm))
      InviteAcceptedResp_MFV_0_5(mc, ms)
    }

    def handleConnReqRedirectedResp(pm: PackedMsg, otherData: Map[String, Any]=Map.empty)
    : MsgCreated_MFV_0_5 = {
      val um = unpackRespMsgs_MPV_0_5(pm, getDIDToUnsealAgentRespMsg)
      parseMsgCreatedRespMsg(um.head)
    }

    def handleMsgSentResp(rmw: PackedMsg, otherData: Map[String, Any]=Map.empty): MsgsSent_MFV_0_5 = {
      val um = unpackRespMsgs_MPV_0_5(rmw, getDIDToUnsealAgentRespMsg)
      parseMsgSentRespMsg(um.head)
    }

    def handleInviteAnsweredResp(connId: String, rmw: PackedMsg):
    MsgCreated_MFV_0_5 = {
      val pcd = pairwiseConnDetail(connId)
      val um = unpackRespMsgs_MPV_0_5(rmw, pcd.myPairwiseDidPair.DID)
      parseMsgCreatedRespMsg(um.head)
    }

    def handleGeneralMsgCreatedResp(rmw: PackedMsg, otherData: Map[String, Any]=Map.empty): GeneralMsgCreatedResp_MFV_0_5 = {
      val um = unpackRespMsgs_MPV_0_5(rmw, getDIDToUnsealAgentRespMsg)
      um.size shouldBe 2
      val mc = parseMsgCreatedRespMsg(um.head)
      val ms = um.tail.headOption.map(hm => parseMsgSentRespMsg(hm))
      GeneralMsgCreatedResp_MFV_0_5(mc, ms)
    }

    def handleGetMsgsResp(rmw: PackedMsg, otherData: Map[String, Any]=Map.empty):
    Msgs_MFV_0_5 = {
      unpackAgentMsg[Msgs_MFV_0_5](rmw.msg)
    }

    def handleGetMsgsRespFromConn(rmw: PackedMsg, otherData: Map[String, Any]=Map.empty): Msgs_MFV_0_5 = {
      unpackAgentMsgFromConn[Msgs_MFV_0_5](rmw.msg, otherData(CONN_ID).toString)
    }

    def handleGetMsgsFromConnsResp(rmw: PackedMsg, otherData: Map[String, Any]=Map.empty):
    MsgsByConns_MFV_0_5 = {
      unpackAgentMsg[MsgsByConns_MFV_0_5](rmw.msg)
    }

    def handleMsgStatusUpdatedByConnsResp(rmw: PackedMsg, otherData: Map[String, Any]=Map.empty):
    MsgStatusUpdatedByConns_MFV_0_5 = {
      unpackAgentMsg[MsgStatusUpdatedByConns_MFV_0_5](rmw.msg)
    }

    private def unpackConfigsUpdatedRespMsg(pmw: PackedMsg, unsealFromDID: DID)
    : ConfigsUpdated_MFV_0_5 = {
      val cum = convertTo[ConfigsUpdated_MFV_0_5](unpackRespMsgs_MPV_0_5(pmw, unsealFromDID).head)
      logApiCallProgressMsg("configsUpdatedMsg: " + cum)
      cum
    }

    private def unpackComMethodUpdatedRespMsg(pmw: PackedMsg, unsealFromDID: DID)
    : ComMethodUpdated_MFV_0_5 = {
      val cum = convertTo[ComMethodUpdated_MFV_0_5](unpackRespMsgs_MPV_0_5(pmw, unsealFromDID).head)
      logApiCallProgressMsg("comMethodUpdatedMsg: " + cum)
      cum
    }


    private def unpackConfigsRemovedRespMsg(pmw: PackedMsg, unsealFromDID: DID)
    : ConfigsRemoved_MFV_0_5 = {
      val crm = convertTo[ConfigsRemoved_MFV_0_5](unpackRespMsgs_MPV_0_5(pmw, unsealFromDID).head)
      logApiCallProgressMsg("configsRemovedMsg: " + crm)
      crm
    }

    private def unpackGetConfigRespMsg(pmw: PackedMsg, unsealFromDID: DID)
    : ConfigsMsg_MFV_0_5 = {
      val gc = convertTo[ConfigsMsg_MFV_0_5](unpackRespMsgs_MPV_0_5(pmw, unsealFromDID).head)
      logApiCallProgressMsg("configsMsg: " + gc)
      gc
    }

    private def unpackKeyCreatedRespMsg(pmw: PackedMsg, unsealFromDID: DID): KeyCreated_MFV_0_5 = {
      val kc = convertTo[KeyCreated_MFV_0_5](unpackRespMsgs_MPV_0_5(pmw, unsealFromDID).head)
      logApiCallProgressMsg("keyCreated: " + kc)
      kc
    }

    def parseMsgCreatedRespMsg(msg: String):
    MsgCreated_MFV_0_5 = {
      val mc = convertTo[MsgCreated_MFV_0_5](msg)
      logApiCallProgressMsg("msgCreated: " + mc)
      mc
    }

    def parseMsgStatusUpdatedRespMsg(msg: String): MsgStatusUpdated_MFV_0_5 = {
      val msu = convertTo[MsgStatusUpdated_MFV_0_5](msg)
      logApiCallProgressMsg("msgStatusUpdated: " + msu)
      msu
    }

    def parseInviteMsgDetailRespMsg(msg: String):
    InviteMsgDetail_MFV_0_5 = {
      val md = convertTo[InviteMsgDetail_MFV_0_5](msg)
      logApiCallProgressMsg("msgDetail: " + md)
      md
    }

    def parseMsgSentRespMsg(msg: String):
    MsgsSent_MFV_0_5 = {
      val ms = convertTo[MsgsSent_MFV_0_5](msg)
      logApiCallProgressMsg("msgSent: " + ms)
      ms
    }

    def parseMsgsRespMsg(msg: String):
    Msgs_MFV_0_5 = {
      val gmr = convertTo[Msgs_MFV_0_5](msg)
      logApiCallProgressMsg("msgs: " + gmr)
      gmr
    }

    def parseMsgsByConnsRespMsg(msg: String):
    MsgsByConns_MFV_0_5 = {
      val gmr = convertTo[MsgsByConns_MFV_0_5](msg)
      logApiCallProgressMsg("msgsByConns: " + gmr)
      gmr
    }

    def parseMsgStatusUpdatedByConnsRespMsg(msg: String):
    MsgStatusUpdatedByConns_MFV_0_5 = {
      val msur = convertTo[MsgStatusUpdatedByConns_MFV_0_5](msg)
      logApiCallProgressMsg("msgStatusUpdatedByConns: " + msur)
      msur
    }

    def handleMsgStatusUpdatedResp(rmw: PackedMsg, otherData: Map[String, Any]=Map.empty): MsgStatusUpdated_MFV_0_5 = {
      unpackPackedMsg[MsgStatusUpdated_MFV_0_5](rmw)
    }

    def handleMsgStatusUpdatedRespFromConn(rmw: PackedMsg, otherData: Map[String, Any]=Map.empty): MsgStatusUpdated_MFV_0_5 = {
      unpackAgentMsgFromConn[MsgStatusUpdated_MFV_0_5](rmw.msg, otherData(CONN_ID).toString)
    }

    def handleConnStatusUpdatedResp(rmw: PackedMsg, otherData: Map[String, Any]=Map.empty): ConnStatusUpdated_MFV_0_5 = {
      unpackPackedMsg[ConnStatusUpdated_MFV_0_5](rmw)
    }

    /**
     * unpack 'message packed' message
     * @param pmw
     * @param unsealFromDID
     * @return
     */
    protected def unpackRespMsgs_MPV_0_5(pmw: PackedMsg, unsealFromDID: String): List[String] = {
      val umw = unsealResp_MPV_0_5(pmw.msg, unsealFromDID)
      umw.agentBundledMsg.msgs.map(_.msg)
    }

    /**
     * unseal 'message packed' message
     * @param rmw
     * @param unsealFromDID
     * @return
     */
    protected def unsealResp_MPV_0_5(rmw: Array[Byte], unsealFromDID: DID)
    : AgentMsgWrapper = {
      val fromKeyInfo = KeyInfo(Right(GetVerKeyByDIDParam(unsealFromDID, getKeyFromPool = false)))
      val unpackTransformDetail = UnpackParam()
      agentMsgTransformer.unpack(rmw, fromKeyInfo, unpackTransformDetail)
    }

  }
}
