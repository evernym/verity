package com.evernym.verity.actor.agent.user

import akka.actor.{PoisonPill, ReceiveTimeout}
import akka.cluster.sharding.ClusterSharding
import akka.testkit.EventFilter
import com.evernym.verity.Status._
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.actor.agent.msghandler.outgoing.ProtocolSyncRespMsg
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, GetRoute, RoutingAgentUtil}
import com.evernym.verity.actor.persistence.{ActorDetail, GetActorDetail}
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily.TypeDetail
import com.evernym.verity.agentmsg.msgfamily.pairwise.{AnswerInviteMsgDetail_MFV_0_5, GetMsgsReqMsg_MFV_0_5, PairwiseMsgUids}
import com.evernym.verity.agentmsg.msgpacker.PackedMsg
import com.evernym.verity.constants.Constants._
import com.evernym.verity.protocol.actor.{ActorProtocol, MsgEnvelope, ProtocolCmd}
import com.evernym.verity.protocol.engine.Constants.MTV_1_0
import com.evernym.verity.protocol.engine.MPV_MSG_PACK
import com.evernym.verity.protocol.protocols.MsgDetail
import com.evernym.verity.protocol.protocols.connecting.v_0_5.{GetInviteDetail_MFV_0_5, ConnectingProtoDef => ConnectingProtoDef_V_0_5}
import com.evernym.verity.testkit.agentmsg.AgentMsgPackagingContext
import com.evernym.verity.testkit.util.{AgentPackMsgUtil, CreateMsg_MFV_0_5, TestConfigDetail, TestUtil}
import com.evernym.verity.vault.{EncryptParam, GetVerKeyByDIDParam, KeyInfo}
import org.scalatest.time.{Seconds, Span}

class ConsumerUserAgentPairwiseSpec_V_0_5 extends UserAgentPairwiseSpec_V_0_5 {
  implicit val msgPackagingContext: AgentMsgPackagingContext =
    AgentMsgPackagingContext(MPV_MSG_PACK, MTV_1_0, packForAgencyRoute = false)
  establishConnByAnsweringInvite()
  sendReceiveMsgSpecs()
}

class EnterpriseUserAgentPairwiseSpec_V_0_5 extends UserAgentPairwiseSpec_V_0_5 {
  implicit val msgPackagingContext: AgentMsgPackagingContext =
    AgentMsgPackagingContext(MPV_MSG_PACK, MTV_1_0, packForAgencyRoute = false)
  establishConnBySendAndReceivingInviteResp()
  sendReceiveMsgSpecs()
}

trait UserAgentPairwiseSpec_V_0_5 extends UserAgentPairwiseSpecScaffolding {
  import mockEdgeAgent.v_0_5_req._
  import mockEdgeAgent.v_0_5_resp._

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupAgency()
    createUserAgent()
    updateComMethod(COM_METHOD_TYPE_PUSH, testPushComMethod)
  }

  def establishConnByAnsweringInvite(): Unit = {
    setupPairwiseAgent(connId1)
    answerInvite(connId1)
  }

  def establishConnBySendAndReceivingInviteResp(): Unit = {
    setupPairwiseAgent(connId1)
    sendInvite(connId1)
    receivedConnAnswerMsg(connId1)
  }

  def sendReceiveMsgSpecs(): Unit = {
    sendInvalidUpdateMsgStatus(connId1)
    sendMsgWithWrongUid(connId1)

    receivedGeneralMsg_V_0_5(connId1, "first cred offer msg", CREATE_MSG_TYPE_CRED_OFFER, expectAlertingPushNotif = true)
    sendGeneralMsg(connId1, "first cred req", CREATE_MSG_TYPE_CRED_REQ)
    receivedGeneralMsg_V_0_5(connId1, "first cred", CREATE_MSG_TYPE_CRED, expectSilentPushNotif = true)
    sendGetMsgsByConns_MFV_0_5("get msg from connections", 1)
    sendGetMsgsFromSingleConn_MFV_0_5(connId1, "first get msgs")
    sendUpdateMsgStatusForCredOfferMsg(connId1, MSG_STATUS_REVIEWED.statusCode)
    sendUpdateMsgStatusForCredUidByConns(MSG_STATUS_REVIEWED.statusCode)

    updateConnStatus(connId1, CONN_STATUS_DELETED.statusCode)
    receivedGeneralMsg_V_0_5(connId1, "second cred offer msg (should not receive push notif for this)", CREATE_MSG_TYPE_CRED_OFFER)

    //NOTE: Below method commented, as to make it working, somehow, in test, I will have to abel to compute pinstId
    //this test is as such not required, it was created to confirm few doubts about how many events
    // protocol is persisting and after recovering if it is abel to get back the same events or not.
    //testConnectingActorRestart()

    restartSpecs()
  }

  def receivedConnAnswerMsg(connId: String): Unit = {
    s"when received CREATE_MSG (connReqAnswer)" - {
      "should be able to respond with MSG_CREATED msg" in {
        val keyDlgProof = mockRemoteEdgeAgent.buildAgentKeyDlgProofForConn(connId)
        val senderDetail = mockRemoteEdgeAgent.buildInviteSenderDetail(connId, Option(keyDlgProof))
        val senderAgencyDetail = mockRemoteEdgeAgent.senderAgencyDetail

        val invite = getLastSentInviteForConnId(connId)

        val msgs = List(CreateMsg_MFV_0_5(
          TypeDetail(MSG_TYPE_CREATE_MSG, MTV_1_0), CREATE_MSG_TYPE_CONN_REQ_ANSWER,
          replyToMsgId = Option(invite.connReqId)),
          AnswerInviteMsgDetail_MFV_0_5(
            TypeDetail(MSG_TYPE_MSG_DETAIL, MTV_1_0),
            senderDetail, senderAgencyDetail, MSG_STATUS_ACCEPTED.statusCode, None)
        )

        val theirAgentEncParam = EncryptParam(
          Set(KeyInfo(Right(GetVerKeyByDIDParam(invite.senderDetail.agentKeyDlgProof.get.agentDID, getKeyFromPool = false)))),
          Option(KeyInfo(Right(GetVerKeyByDIDParam(keyDlgProof.agentDID, getKeyFromPool = false))))
        )

        val msg = buildReceivedReqMsg_V_0_5(AgentPackMsgUtil(msgs, theirAgentEncParam)(mockEdgeAgent.v_0_5_req.msgPackVersion))
        uap ! wrapAsPackedMsgParam(msg)
        expectMsgType[PackedMsg]
      }
    }
  }

  def setupPairwiseAgent(connId: String): Unit = {

    "UserAgentPairwise setup" - {

      "UserAgent" - {
        "when sent CREATE_KEY" - {
          "should spin up new pairwise actor" in {
            val msg = prepareCreateKeyMsgForAgent(connId)
            ua ! wrapAsPackedMsgParam(msg)
            val pm = expectMsgType[PackedMsg]
            val keyCreated = handleKeyCreatedResp(pm, Map(CONN_ID -> connId))
            pairwiseDID = keyCreated.withPairwiseDID

            prepareConnReqChangesOnRemoteEdgeAgent(connId)
          }
        }

        "when sent get route to routing agent" - {
          "should be able to get persistence id of newly created pairwise actor" in {
            val bucketId = RoutingAgentUtil.getBucketEntityId(pairwiseDID)
            agentRouteStoreRegion ! ForIdentifier(bucketId, GetRoute(pairwiseDID))
            val addressDetail = expectMsgType[Option[ActorAddressDetail]]
            addressDetail.isDefined shouldBe true
            userAgentPairwiseEntityId = addressDetail.get.address
          }
        }
      }
    }
  }

  def sendInvite(connId: String): Unit = {

    "when sent CREATE_MSG (conn req) msg with unsupported version" - {
      "should respond with unsupported version error msg" in {
        val msg = prepareCreateInviteMsgWithVersion(unsupportedVersion, connId)
        uap ! wrapAsPackedMsgParam(msg)
        expectError(UNSUPPORTED_MSG_TYPE.statusCode)
      }
    }

    "when sent UPDATE_CONFIGS (to update name and logo url)" - {
      "should respond with  CONFIGS_UPDATED msg" in {
        val updateNameConf = TestConfigDetail(NAME_KEY, Option(edgeAgentName))
        val updateLogoUrlConf = TestConfigDetail(LOGO_URL_KEY, Option(edgeAgentLogoUrl))
        val msg = prepareUpdateConfigsForAgent(Set(updateNameConf, updateLogoUrlConf))
        ua ! wrapAsPackedMsgParam(msg)
        val pm = expectMsgType[PackedMsg]
        handleConfigsUpdatedResp(pm)
      }
    }

    "when sent CREATE_MSG (conn req) msg after setting required configs" - {
      "should respond with MSG_CREATED with invitation detail" in {
        val msg = prepareCreateInviteMsg(connId1, includeSendMsg = true, Some(phoneNo))
        uap ! wrapAsPackedMsgParam(msg)
        val pm = expectMsgType[PackedMsg]
        assert(checkCreateInviteResponse(pm, connId))
        val icr = handleInviteCreatedResp(pm, buildConnIdMap(connId))
        inviteDetail = icr.md.inviteDetail

        inviteDetail.senderDetail.name.contains(edgeAgentName) shouldBe true
        inviteDetail.senderDetail.logoUrl.contains(edgeAgentLogoUrl) shouldBe true
      }
    }

    def checkCreateInviteResponse(resp: PackedMsg, connId: String): Boolean = {
      val icr = handleInviteCreatedResp(resp, buildConnIdMap(connId))
      TestUtil.encodedUrl(icr.md.urlToInviteDetail) == icr.md.urlToInviteDetailEncoded
    }

    s"when sent GET_MSGS msg after sending invite" - {
      "should response with MSGS" in {
        eventually (timeout(Span(5, Seconds))) {
          val msg = prepareGetMsgsFromConn(connId)
          uap ! wrapAsPackedMsgParam(msg)
          val pm = expectMsgType[PackedMsg]
          val allMsgs = handleGetMsgsRespFromConn(pm, buildConnIdMap(connId))
          val connReqMsgOpt = allMsgs.msgs.find(_.`type` == CREATE_MSG_TYPE_CONN_REQ)
          connReqMsgOpt.isDefined shouldBe true
          connReqMsgOpt.get.statusCode shouldBe MSG_STATUS_SENT.statusCode
        }
      }
    }

    "when sent GetInviteDetail with invalid msg uid" - {
      "should respond with data not found error msg" in {
        uap ! GetInviteDetail_MFV_0_5("uid")
        expectError(DATA_NOT_FOUND.statusCode)
      }
    }

    "when sent GetInviteDetail with valid msg uid" - {
      "should respond with invite detail" in {
        uap ! GetInviteDetail_MFV_0_5(inviteDetail.connReqId)
        expectInviteDetail(inviteDetail.connReqId)
      }
    }
  }

  def answerInvite(connId: String): Unit = {

    "when sent CREATE_MSG (conn req answer) msg" - {
      "should respond with MSG_CREATED msg" in {
        val msg = prepareCreateAnswerInviteMsgForAgent(connId, includeSendMsg = true, inviteDetail)
        uap ! wrapAsPackedMsgParam(msg)
        expectMsgType[PackedMsg]
      }
    }

    "when resent CREATE_MSG (conn req answer) msg" - {
      "should respond with proper error response" taggedAs (UNSAFE_IgnoreLog) in {
        val msg = prepareCreateAnswerInviteMsgForAgent(
          connId, includeSendMsg = true, inviteDetail)
        uap ! wrapAsPackedMsgParam(msg)
        expectError(ACCEPTED_CONN_REQ_EXISTS.statusCode)
      }
    }
  }

  def sendGeneralMsg(connId: String, hint: String, msgType: String): Unit = {
    s"when sent CREATE_MSG ($msgType) msg [$hint]" - {
      "should respond with MSG_CREATED msg" in {
        val msg = prepareCreateGeneralMsgForConn(
          connId, includeSendMsg = true, msgType, PackedMsg("msg-data".getBytes), None)
        uap ! wrapAsPackedMsgParam(msg)
        expectMsgType[PackedMsg]
      }
    }

    s"when sent SEND_MSGS with unsupported version [$hint]" - {
      "should respond with unsupported version error msg" taggedAs (UNSAFE_IgnoreLog) in {
        val msg = prepareSendMsgsForConnForAgency(unsupportedVersion,
          connId, List(inviteDetail.connReqId))
        uap ! wrapAsPackedMsgParam(msg)
        expectError(UNSUPPORTED_MSG_TYPE.statusCode)
      }
    }
  }

  def sendInvalidUpdateMsgStatus(connId: String): Unit = {
    "when sent UPDATE_MSG_STATUS for already answered msg" - {
      "should respond with appropriate error msg" in {
        eventually(timeout(Span(5, Seconds))) {
          val msg = prepareUpdateMsgStatusForConn(
            connId, List(inviteDetail.connReqId), MSG_STATUS_ACCEPTED.statusCode)
          uap ! wrapAsPackedMsgParam(msg)
          expectError(MSG_VALIDATION_ERROR_ALREADY_ANSWERED.statusCode)
        }
      }
    }

    "when sent UPDATE_MSG_STATUS with invalid status" - {
      "should respond with appropriate error msg" in {
        val msg = prepareUpdateMsgStatusForConn(
          connId, List(inviteDetail.connReqId), MSG_DELIVERY_STATUS_SENT.statusCode)
        uap ! wrapAsPackedMsgParam(msg)
        expectError(INVALID_VALUE.statusCode)
      }
    }
  }

  def sendUpdateMsgStatusForCredOfferMsg(connId: String, statusCode: String): Unit = {
    s"when sent valid UPDATE_MSG_STATUS for cred offer msg" - {
      "should respond with MSG_STATUS_UPDATED msg" in {
        updateMsgStatusToConn(connId, statusCode, List(credOfferUid))
      }
    }

    "when send UPDATE_MSG_STATUS with unsupported version" - {
      "should respond with unsupported version error msg" in {
        updateMsgStatusWithUnsupportedVersion(connId)
      }
    }
  }

  private def updateMsgStatusByConns(statusCode: String, uidsByConnIds: Map[String, List[String]]): Unit = {
    val msgUidsByConns = uidsByConnIds.map { case (k, v) =>
      val connDetail = mockEdgeAgent.pairwiseConnDetail(k)
      PairwiseMsgUids(connDetail.myPairwiseDidPair.DID, v)
    }.toList

    val msg = prepareUpdateMsgStatusByConns(msgUidsByConns, statusCode)
    ua !  wrapAsPackedMsgParam(msg)

    val pm = expectMsgType[PackedMsg]
    handleMsgStatusUpdatedByConnsResp(pm)
  }

  def sendUpdateMsgStatusForCredUidByConns(statusCode: String): Unit = {
    s"when sent valid UPDATE_MSG_STATUS_BY_CONNS msg for cred msg" - {
      "should respond with MSG_STATUS_UPDATED_BY_CONNS" in {
        val uidsByConns = Map(connId1 -> List(credUid))
        updateMsgStatusByConns(statusCode, uidsByConns)
      }
    }
  }

  def sendUpdateMsgStatusForCredReqByConns(statusCode: String): Unit = {
    "when sent UPDATE_MSG_STATUS_BY_CONNS msg for cred req msg" - {
      "should respond with MSG_STATUS_UPDATED_BY_CONNS msg" in {
        val uidsByConns = Map(connId1 -> List(credReqUid))
        updateMsgStatusByConns(statusCode, uidsByConns)
      }
    }
  }

  def updateConnStatus(connId: String, newStatusCode: String): Unit = {

    "when sent UPDATE_CONN_STATUS msg mark it delete" - {
      "should respond with CONN_STATUS_UPDATED msg" in {
        val msg = prepareUpdateConnStatusMsg(connId, newStatusCode)
        uap ! wrapAsPackedMsgParam(msg)
        expectMsgType[PackedMsg]
      }
    }

    "when sent UPDATE_CONN_STATUS with unsupported version" - {
      "should respond with unsupported version error msg" in {
        val msg = prepareUpdateConnStatusMsg(connId, CONN_STATUS_DELETED.statusCode, unsupportedVersion)
        uap ! wrapAsPackedMsgParam(msg)
        expectError(UNSUPPORTED_MSG_TYPE.statusCode)
      }
    }
  }

  def sendMsgWithWrongUid(connId: String): Unit = {
    "when sent SendMsgs with wrong uid" - {
      "should respond with DATA_NOT_FOUND error ,sg" in {
        val msg = prepareSendMsgsForConnForAgency(connId, List("randomUID"))
        uap ! wrapAsPackedMsgParam(msg)
        expectError(DATA_NOT_FOUND.statusCode)
      }
    }
  }

  def testReceiveTimeOut(): Unit = {
    "UserPairwiseAgent - receive timeout" - {
      "when sent ReceiveTimeout" - {
        "should be stopped successfully" in {
          EventFilter.debug(pattern = "received ReceiveTimeout", occurrences = 1) intercept {
            uap ! ReceiveTimeout
          }
        }
      }
    }
  }

  def testConnectingActorRestart(): Unit = {
    "connecting actor" - {
      lazy val cap = new ActorProtocol(ConnectingProtoDef_V_0_5)
      lazy val connectingActorId = ??? //TODO: if we want to make this test working, we should be able to compute pinstId here
      lazy val connectingRegion = ClusterSharding.get(system).shardRegion(cap.typeName)

      var actorDetailBeforeRestart: ActorDetail = null

      "when sent GetTotalEvents message" - {
        "should respond with TotalEvents message" in {
          connectingRegion ! ForIdentifier(connectingActorId, GetActorDetail)
          val ad = expectMsgType[ActorDetail]
          actorDetailBeforeRestart = ad
        }
      }

      "when sent PoisonPill message" - {
        "should be stopped" in {
          connectingRegion ! ForIdentifier(connectingActorId, PoisonPill)
          expectNoMessage()
        }
      }

      "when sent GetMsgs" - {
        "should respond with available messages" in {
          val mtd = TypeDetail(MSG_TYPE_GET_MSGS, MTV_1_0)
          val msg = GetMsgsReqMsg_MFV_0_5(mtd, None)
          val pme = MsgEnvelope(msg, msg.typedMsg.msgType, UNKNOWN_RECIP_PARTICIPANT_ID, UNKNOWN_SENDER_PARTICIPANT_ID)
          connectingRegion ! ForIdentifier(connectingActorId, ProtocolCmd(pme, null, null))
          val srm = expectMsgType[ProtocolSyncRespMsg]
          val msgs = srm.msg.asInstanceOf[List[MsgDetail]]
        }
      }

      "when sent GetTotalEvents message after first message post restart" - {
        "should respond with TotalEvents message" in {
          connectingRegion ! ForIdentifier(connectingActorId, GetActorDetail)
          val lad = expectMsgType[ActorDetail]
          lad.totalRecoveredEvents shouldBe actorDetailBeforeRestart.totalPersistedEvents
        }
      }

    }
  }

}