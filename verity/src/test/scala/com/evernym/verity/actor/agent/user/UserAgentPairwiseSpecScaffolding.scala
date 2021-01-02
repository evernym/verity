package com.evernym.verity.actor.agent.user

import akka.actor.PoisonPill
import com.evernym.verity.Status._
import com.evernym.verity.actor.agent.{SetupAgentEndpoint, SetupAgentEndpoint_V_0_7, SponsorRel}
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, GetRoute, RoutingAgentUtil}
import com.evernym.verity.actor.persistence.{ActorDetail, GetActorDetail}
import com.evernym.verity.actor.testkit.{AgentSpecHelper, PersistentActorSpec}
import com.evernym.verity.actor.{ForIdentifier, agentRegion}
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgpacker.PackMsgParam
import com.evernym.verity.actor.testkit.checks.{UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog}
import com.evernym.verity.protocol.engine.{DEFAULT_THREAD_ID, DID, ThreadId}
import com.evernym.verity.protocol.protocols.MsgDetail
import com.evernym.verity.protocol.protocols.agentprovisioning.common.AgentWalletSetupProvider
import com.evernym.verity.protocol.protocols.connecting.common.InviteDetail
import com.evernym.verity.push_notification.MockPusher
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.testkit.agentmsg.AgentMsgPackagingContext
import com.evernym.verity.testkit.mock.agency_admin.MockAgencyAdmin
import com.evernym.verity.testkit.mock.cloud_agent.MockEntCloudAgent
import com.evernym.verity.testkit.mock.edge_agent.{MockEdgeAgent, MockEntEdgeAgent}
import com.evernym.verity.testkit.util.AgentPackMsgUtil._
import com.evernym.verity.testkit.util._
import com.evernym.verity.util.MsgIdProvider
import com.evernym.verity.UrlParam
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_MSG_PACK
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.wallet.PackedMsg
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}


trait UserAgentPairwiseSpecScaffolding
  extends BasicSpec
    with PersistentActorSpec
    with AgentSpecHelper
    with AgentWalletSetupProvider
    with Eventually {

  implicit def msgPackagingContext: AgentMsgPackagingContext

  val mockEntAgencyAdmin: MockAgencyAdmin =
    new MockAgencyAdmin(system, UrlParam("localhost:9002"), platform.agentActorContext.appConfig)

  lazy val mockRemoteEdgeAgent: MockEntEdgeAgent =
    buildMockEnterpriseEdgeAgent(platform.agentActorContext.appConfig, mockEntAgencyAdmin)

  lazy val mockRemoteEdgeCloudAgent: MockEntCloudAgent =
    buildMockEntCloudAgent(platform.agentActorContext.appConfig, mockEntAgencyAdmin)

  lazy val mockEdgeAgent: MockEdgeAgent =
    buildMockConsumerEdgeAgent(platform.agentActorContext.appConfig, mockAgencyAdmin)

  val testPushComMethod: String = s"${MockPusher.comMethodPrefix}:12345"

  var pairwiseDID: DID = _

  var threadId: ThreadId = _
  var inviteDetail:InviteDetail = _
  var credOfferUid: String = _
  var credReqUid: String = _
  var credUid: String = _

  import mockEdgeAgent.v_0_5_req._
  import mockEdgeAgent.v_0_5_resp._

  //fixture for common user agent pairwise used across tests in this scope
  def uap = agentRegion(userAgentPairwiseEntityId, userAgentPairwiseRegionActor)

  def getLastSentInviteByRemoteEdgeAgentForConnId(connId: String): InviteDetail =
    mockRemoteEdgeAgent.pairwiseConnDetail(connId).lastSentInvite

  def getLastSentInviteForConnId(connId: String): InviteDetail =
    mockEdgeAgent.pairwiseConnDetail(connId).lastSentInvite

  def checkIfAlertingPushNotifSent(): Boolean = {
    //TODO: come back to this
    true
  }

  def checkIfSilentPushNotifSent(): Boolean = {
    //TODO: come back to this
    true
  }

  def checkPushNotif(expectSilentPushNotif: Boolean, expectAlertingPushNotif: Boolean, oldPushMsgCount: Int): Boolean = {
    if (expectSilentPushNotif) checkIfSilentPushNotifSent()
    else if (expectAlertingPushNotif) checkIfAlertingPushNotifSent()
    else MockPusher.pushedMsg.size == oldPushMsgCount
  }

  def setPairwiseEntityId(agentPairwiseDID: DID): Unit = {
    val bucketId = RoutingAgentUtil.getBucketEntityId(agentPairwiseDID)
    agentRouteStoreRegion ! ForIdentifier(bucketId, GetRoute(agentPairwiseDID))
    val addressDetail = expectMsgType[Option[ActorAddressDetail]]
    addressDetail.isDefined shouldBe true
    userAgentPairwiseEntityId = addressDetail.get.address
  }

  def updateMsgStatusToConn(connId: String, statusCode: String, msgUids: List[String]): Unit = {
    val msg = prepareUpdateMsgStatusForConn(connId, msgUids, statusCode)
    uap ! wrapAsPackedMsgParam(msg)
    expectMsgType[PackedMsg]
  }

  def updateMsgStatusWithUnsupportedVersion(connId: String): Unit = {
    val msg = prepareUpdateMsgStatusForConn(connId,
      List(inviteDetail.connReqId), MSG_STATUS_ACCEPTED.statusCode, unsupportedVersion)
    uap ! wrapAsPackedMsgParam(msg)
    expectError(UNSUPPORTED_MSG_TYPE.statusCode)
  }

  def createUserAgent(): Unit = {
    val userDID = mockEdgeAgent.myDIDDetail.did
    val userDIDVerKey = mockEdgeAgent.getVerKeyFromWallet(userDID)
    val agentPairwiseKey = prepareNewAgentWalletData(userDID, userDIDVerKey, userAgentEntityId)

    ua ! SetupAgentEndpoint(userDID, agentPairwiseKey.did)
    expectMsg(Done)

    mockEdgeAgent.handleAgentCreatedRespForAgent(agentPairwiseKey.did, agentPairwiseKey.verKey)
  }

  def createUserAgent_0_7(): Unit = {
    val userDID = mockEdgeAgent.myDIDDetail.did
    val userDIDVerKey = mockEdgeAgent.getVerKeyFromWallet(userDID)
    val agentPairwiseKey = prepareNewAgentWalletData(userDID, userDIDVerKey, userAgentEntityId)

    ua ! SetupAgentEndpoint_V_0_7(
      DEFAULT_THREAD_ID,
      userDID,
      agentPairwiseKey.did,
      mockEdgeAgent.myDIDDetail.verKey,
      Some(SponsorRel("evernym-test-sponsor", "sponsee-id"))
    )

    expectMsgType[AgentProvisioningDone]
    mockEdgeAgent.handleAgentCreatedRespForAgent(agentPairwiseKey.did, agentPairwiseKey.verKey)
  }

  def updateComMethod(comMethodType: Int, comMethod: String): Unit = {
    val updateReq = prepareUpdateComMethodMsgForAgent(
      TestComMethod (MsgIdProvider.getNewMsgId, comMethodType, Option(comMethod)))
    ua ! wrapAsPackedMsgParam(updateReq)
    val pm = expectMsgType[PackedMsg]
    handleComMethodUpdatedResp(pm).isInstanceOf[ComMethodUpdated_MFV_0_5]
  }

  def prepareConnReqChangesOnRemoteEdgeAgent(connId: String): Unit = {
    mockRemoteEdgeAgent.setInviteData(connId, mockRemoteEdgeCloudAgent)
    inviteDetail = getLastSentInviteByRemoteEdgeAgentForConnId(connId)

    val lpcd = mockEdgeAgent.pairwiseConnDetail(connId)
    val rcapcd = mockRemoteEdgeCloudAgent.pairwiseConnDetail(connId)
    rcapcd.setTheirCloudAgentPairwiseDidPair(lpcd.myCloudAgentPairwiseDidPair.DID, lpcd.myCloudAgentPairwiseDidPair.verKey)
  }

  def prepareConnReqAnswerChangesOnRemoteEdgeAgent(connId: String): Unit = {
    val le = mockRemoteEdgeAgent.addNewLocalPairwiseKey(connId)
    val rec = mockRemoteEdgeCloudAgent.pairwiseConnDetail(connId)
    le.setMyCloudAgentPairwiseDidPair(rec.myPairwiseDidPair.DID, rec.myPairwiseDidPair.verKey)
    rec.setTheirPairwiseDidPair(le.myPairwiseDidPair.DID, le.myPairwiseDidPair.verKey)
  }

  def sendGetMsgsFromSingleConn_MFV_0_5(connId: String, hint: String): Unit = {

    s"when sent GET_MSGS msg [$hint]" - {
      "should response with MSGS" taggedAs (UNSAFE_IgnoreLog) in {
        val msg = prepareGetMsgsFromConn(connId)
        uap ! wrapAsPackedMsgParam(msg)
        val pm = expectMsgType[PackedMsg]

        val allMsgs = handleGetMsgsRespFromConn(pm, buildConnIdMap(connId))
        val emptyMsgDetails = MsgDetail("", "", "", "", None, None, None, Set.empty)
        credOfferUid = allMsgs.msgs.find(_.`type`== CREATE_MSG_TYPE_CRED_OFFER).getOrElse(emptyMsgDetails).uid
        credReqUid = allMsgs.msgs.find(_.`type`== CREATE_MSG_TYPE_CRED_REQ).getOrElse(emptyMsgDetails).uid
        credUid = allMsgs.msgs.find(_.`type`== CREATE_MSG_TYPE_CRED).getOrElse(emptyMsgDetails).uid
      }
    }

    "when sent GET_MSGS with unsupported version" - {
      "should respond with unsupported version error msg" in {
        val msg = prepareGetMsgsFromConn(connId, msgTypeVersion = unsupportedVersion)
        uap ! wrapAsPackedMsgParam(msg)
        expectError(UNSUPPORTED_MSG_TYPE.statusCode)
      }
    }
  }

  def sendGetMsgsByConns_MFV_0_5(hint: String, expectedConnsCount: Int): Unit = {
    s"when sent GET_MSGS_BY_CONNS routed Msg [$hint]" - {
      "should respond with MSGS_BY_CONNS msg" taggedAs (UNSAFE_IgnoreLog) in {
        val msg = prepareGetMsgsFromConns()
        ua ! wrapAsPackedMsgParam(msg)
        val pm = expectMsgType[PackedMsg]
        val getMsgsByConns = handleGetMsgsFromConnsResp(pm)
        getMsgsByConns.msgsByConns.size shouldBe expectedConnsCount
      }
    }
  }

  def checkMsgsSentToEdgeAgentIfReq(check: Boolean, oldMsgsSentCount: Int): Unit = {
    if (check) {
      eventually(timeout(Span(5, Seconds))) {
        getTotalAgentMsgSentByCloudAgent shouldBe oldMsgsSentCount + 1
      }
    }
  }

  def buildReceivedReqMsg_V_0_5(pmp: PackMsgParam) : PackedMsg = {
    preparePackedRequestForAgent(pmp)(MPF_MSG_PACK, mockRemoteEdgeCloudAgent.agentMsgTransformer,
      mockRemoteEdgeCloudAgent.wap)
  }

  def receivedGeneralMsgWithoutAcceptingInvite_V_0_5(
                                                      connId: String, hint: String,
                                                      msgType: String, expectSilentPushNotif: Boolean = false,
                                                      expectAlertingPushNotif: Boolean = false): Unit = {
    s"when received CREATE_MSG ($msgType) msg [$hint]" - {
      "should respond with error" in {
        val oldPushMsgCount = MockPusher.pushedMsg.size
        val coreMsg = buildCoreCreateGeneralMsg(includeSendMsg = true, msgType,
          PackedMsg("msg-data".getBytes), None, None)(mockRemoteEdgeCloudAgent.encryptParamForOthersPairwiseKey(connId))
        val msg = buildReceivedReqMsg_V_0_5(coreMsg)
        uap ! wrapAsPackedMsgParam(msg)
        expectError(UNAUTHORIZED.statusCode)
      }
    }
  }


  def receivedGeneralMsg_V_0_5(connId: String, hint: String, msgType: String,
                               expectSilentPushNotif: Boolean = false,
                               expectAlertingPushNotif: Boolean = false,
                               checkForMsgsSentToEdgeAgent: Boolean = false): Unit = {
    s"when received CREATE_MSG ($msgType) msg [$hint]" - {
      "should respond with MSG_CREATED msg" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
        eventually {
          val currentMsgsSent = getTotalAgentMsgSentByCloudAgent
          val oldPushMsgCount = MockPusher.pushedMsg.size
          val coreMsg = buildCoreCreateGeneralMsg(includeSendMsg = true, msgType,
            PackedMsg("msg-data".getBytes), None, None)(mockRemoteEdgeCloudAgent.encryptParamForOthersPairwiseKey(connId))
          val msg = buildReceivedReqMsg_V_0_5(coreMsg)
          uap ! wrapAsPackedMsgParam(msg)
          expectMsgType[PackedMsg]
          checkPushNotif(expectSilentPushNotif, expectAlertingPushNotif, oldPushMsgCount)
          checkMsgsSentToEdgeAgentIfReq(checkForMsgsSentToEdgeAgent, currentMsgsSent)
        }
      }
    }
  }

  protected def restartSpecs(): Unit = {
    "when tried to restart actor" - {
      "should be successful and respond" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
        uap ! PoisonPill
        expectNoMessage()
        Thread.sleep(1000)
        uap ! GetActorDetail
        expectMsgType[ActorDetail]
      }
    }
  }
}



