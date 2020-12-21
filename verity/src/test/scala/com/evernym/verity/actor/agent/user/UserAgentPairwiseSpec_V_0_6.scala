package com.evernym.verity.actor.agent.user

import com.evernym.verity.constants.Constants.{COM_METHOD_TYPE_HTTP_ENDPOINT, COM_METHOD_TYPE_PUSH, DEFAULT_INVITE_SENDER_LOGO_URL, DEFAULT_INVITE_SENDER_NAME}
import com.evernym.verity.actor.agent.msghandler.incoming.PackedMsgParam
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.{CREATE_MSG_TYPE_CRED_OFFER, MSG_TYPE_DETAIL_CONN_REQ_ACCEPTED, getNewMsgUniqueId}
import com.evernym.verity.agentmsg.msgfamily.pairwise.ConnReqAcceptedMsg_MFV_0_6
import com.evernym.verity.actor.agent.Thread
import com.evernym.verity.agentmsg.msgpacker.PackMsgParam
import com.evernym.verity.protocol.engine.Constants.MTV_1_0
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.testkit.agentmsg.AgentMsgPackagingContext
import com.evernym.verity.testkit.util.AgentPackMsgUtil
import com.evernym.verity.testkit.util.AgentPackMsgUtil.preparePackedRequestForAgent
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.vault.{EncryptParam, GetVerKeyByDIDParam, KeyInfo}
import org.scalatest.time.{Seconds, Span}

class ConsumerAgentPairwiseBaseSpec_V_0_6 extends UserAgentPairwiseSpec_V_0_6 {

  implicit val msgPackagingContext: AgentMsgPackagingContext =
    AgentMsgPackagingContext(MPF_INDY_PACK, MTV_1_0, packForAgencyRoute = false)

  createKeySpecs(connId1New)
  declineInviteSpecs(connId1New)
  receivedGeneralMsgWithoutAcceptingInvite_V_0_5(connId1New, "cred offer msg",
    CREATE_MSG_TYPE_CRED_OFFER, expectAlertingPushNotif = true)

  createKeySpecs(connId2New)
  acceptInviteSpecs(connId2New)
  receivedGeneralMsg_V_0_5(connId2New, "first cred offer msg",
    CREATE_MSG_TYPE_CRED_OFFER, expectAlertingPushNotif = true)
  sendRemoteMsg(connId2New, "cred-req", "credReq")
  restartSpecs()
}

class EnterpriseAgentPairwiseBaseSpec_V_0_6 extends UserAgentPairwiseSpec_V_0_6 {

  implicit val msgPackagingContext: AgentMsgPackagingContext =
    AgentMsgPackagingContext(MPF_INDY_PACK, MTV_1_0, packForAgencyRoute = false)

  createKeySpecs(connId1New)
  sendInviteSpecs(connId1New)
  receivedConnReqAcceptedSpecs(connId1New)
  sendGetMsgsFromSingleConn_MFV_0_5(connId1New, "first get msgs")
  sendGetMsgsByConns_MFV_0_5("get msg from connections", 1)
  sendRemoteMsg(connId1New, "cred-offer", "credOffer")
  restartSpecs()
}

class VerityAgentPairwiseSpec_V_0_6 extends UserAgentPairwiseSpec_V_0_6 {

  implicit val msgPackagingContext: AgentMsgPackagingContext =
    AgentMsgPackagingContext(MPF_INDY_PACK, MTV_1_0, packForAgencyRoute = false)

  val connId = "connId"
  createConnectionSpec(connId)
}


trait UserAgentPairwiseSpec_V_0_6
  extends UserAgentPairwiseSpecScaffolding {

  import mockEdgeAgent.v_0_6_req._
  import mockEdgeAgent.v_0_6_resp._

  implicit def msgPackagingContext: AgentMsgPackagingContext

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupAgency()
    createUserAgent()
    updateComMethod(COM_METHOD_TYPE_PUSH, testPushComMethod)
    updateComMethod(COM_METHOD_TYPE_HTTP_ENDPOINT, "localhost:7000")
  }

  var agentPairwiseDID: DID = _

  val connId1New = "connIdNew1"
  val connId2New = "connIdNew2"

  def createConnectionSpec(connId: String): Unit = {

    s"when sent CREATE_CONNECTION msg ($connId)" - {

      "should respond with CONNECTION_CREATED msg" in {
        val (resp, receivedMsgOpt) = withExpectNewMsgAtRegisteredEndpoint {
          val msg = prepareCreateConnection(Option(connId), Option(phoneNo))
          ua ! PackedMsgParam(msg, reqMsgContext)
          expectMsgType[PackedMsg] //this expectation of a message is temporary until we start returning generic success messages upon receiving agent messages.
        }
        val agentMsg = mockEdgeAgent.handleReceivedAgentMsg(receivedMsgOpt.map(_.msg).get)
      }
    }

    //test connection request accepted msg: MSG_TYPE_CONN_REQ_ACCEPTED

  }

  def createKeySpecs(connId: String): Unit = {

    s"when sent CREATE_KEY msg ($connId)" - {
      "should respond with KEY_CREATED msg" taggedAs (UNSAFE_IgnoreLog) in {
        val msg = preparePairwiseCreateKey(mockEdgeAgent.cloudAgentDetailReq.DID, connId)
        ua ! PackedMsgParam(msg, reqMsgContext)
        val pm = expectMsgType[PackedMsg]
        val resp = handlePairwiseKeyCreatedResp(pm, buildConnIdMap(connId))
        agentPairwiseDID = resp.withPairwiseDID
      }
    }

    s"when sent get route to routing agent ($connId)" - {
      "should be able to get persistence id of newly created pairwise actor" in {
        setPairwiseEntityId(agentPairwiseDID)
      }
    }
  }

  def sendInviteSpecs(connId1New: String): Unit = {
    "when sent connection request msg" - {
      "should respond with connection request detail" taggedAs (UNSAFE_IgnoreLog) in {
        val msg = prepareCreateInvite(
          mockEdgeAgent.pairwiseConnDetail(connId1New).myCloudAgentPairwiseDidPair.DID,
          Option(connId1New), includeKeyDlgProof = true)
        uap ! PackedMsgParam(msg, reqMsgContext)
        val pm = expectMsgType[PackedMsg]
        val icr = handleInviteCreatedResp(pm, buildConnIdMap(connId1New))
        threadId = icr.`~thread`.thid.get
        inviteDetail = icr.inviteDetail
        inviteDetail.senderDetail.name.contains(DEFAULT_INVITE_SENDER_NAME) shouldBe true
        inviteDetail.senderDetail.logoUrl.contains(DEFAULT_INVITE_SENDER_LOGO_URL) shouldBe true
        val inviterAgentKeyDlfProof = inviteDetail.senderDetail.agentKeyDlgProof.get
        val pcd = mockRemoteEdgeCloudAgent.addNewLocalPairwiseKey(connId1New)
        pcd.setTheirCloudAgentPairwiseDidPair(
          inviterAgentKeyDlfProof.agentDID, inviterAgentKeyDlfProof.agentDelegatedKey)

        prepareConnReqAnswerChangesOnRemoteEdgeAgent(connId1New)
      }
    }
  }

  def buildReceivedReqMsg_1_0(pmp: PackMsgParam) : PackedMsg = {
    preparePackedRequestForAgent(pmp)(MPF_INDY_PACK, mockRemoteEdgeCloudAgent.agentMsgTransformer,
      mockRemoteEdgeCloudAgent.wap)
  }


  def receivedConnReqAcceptedSpecs(connId: String): Unit = {
    s"when received CONN_REQ_ACCEPTED" - {
      "should be able to respond with MSG_CREATED msg" taggedAs (UNSAFE_IgnoreLog) in {

        val totalMsgsSentByCloudAgent = getTotalAgentMsgSentByCloudAgent

        val keyDlgProof = mockRemoteEdgeAgent.buildAgentKeyDlgProofForConn(connId)
        val senderDetail = mockRemoteEdgeAgent.buildInviteSenderDetail(connId, Option(keyDlgProof))
        val senderAgencyDetail = mockRemoteEdgeAgent.senderAgencyDetail

        val invite = getLastSentInviteForConnId(connId)

        val agentMsg = ConnReqAcceptedMsg_MFV_0_6(
          MSG_TYPE_DETAIL_CONN_REQ_ACCEPTED,
          getNewMsgUniqueId,
          Thread(Option(threadId)),
          sendMsg = false,
          senderDetail,
          senderAgencyDetail,
          invite.connReqId)

        val theirAgentEncParam = EncryptParam(
          Set(KeyInfo(Right(GetVerKeyByDIDParam(invite.senderDetail.agentKeyDlgProof.get.agentDID, getKeyFromPool = false)))),
          Option(KeyInfo(Right(GetVerKeyByDIDParam(keyDlgProof.agentDID, getKeyFromPool = false))))
        )
        val msg = buildReceivedReqMsg_1_0(AgentPackMsgUtil(agentMsg, theirAgentEncParam))
        uap ! wrapAsPackedMsgParam(msg)
        expectMsgType[PackedMsg]

        eventually (timeout(Span(5, Seconds))) {
          getTotalAgentMsgSentByCloudAgent shouldBe totalMsgsSentByCloudAgent + 1
        }

      }
    }
  }

  def declineInviteSpecs(connId: String): Unit = {
    s"when sent DECLINE_CONN_REQ" - {
      "should be able to respond with MSG_CREATED msg" in {
        prepareConnReqChangesOnRemoteEdgeAgent(connId)
        val msg = prepareDeclineInviteMsgForAgent(connId, inviteDetail)
        uap ! wrapAsPackedMsgParam(msg)
        expectMsgType[PackedMsg]
      }
    }
  }

  def acceptInviteSpecs(connId: String): Unit = {

    s"when sent ACCEPT_CONN_REQ" - {
      "should be able to respond with MSG_CREATED msg" in {
        prepareConnReqChangesOnRemoteEdgeAgent(connId)
        val msg = prepareAcceptInviteMsgForAgent(connId, includeSendMsg = true, inviteDetail)
        uap ! wrapAsPackedMsgParam(msg)
        expectMsgType[PackedMsg]
      }
    }
  }

  def sendRemoteMsg(connId: String, hint: String, msgType: String): Unit = {
    s"when sent SEND_REMOTE_MSG ($msgType) msg [$hint]" - {
      "should respond with REMOTE_MSG_SENT msg" taggedAs (UNSAFE_IgnoreLog) in {
        val msg = prepareSendRemoteMsgForConn(
          connId, sendMsg = true, msgType, PackedMsg("msg-data".getBytes), None)
        uap ! wrapAsPackedMsgParam(msg)
        expectMsgType[PackedMsg]
      }
    }
  }
}
