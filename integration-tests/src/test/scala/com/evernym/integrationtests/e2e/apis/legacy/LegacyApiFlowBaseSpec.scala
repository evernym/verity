package com.evernym.integrationtests.e2e.apis.legacy

import akka.actor.ActorSystem
import com.evernym.integrationtests.e2e.TestConstants
import com.evernym.integrationtests.e2e.client.{AdminClient, ApiClientCommon}
import com.evernym.integrationtests.e2e.env.AppInstance.AppInstance
import com.evernym.integrationtests.e2e.env.EnvUtils.IntegrationEnv
import com.evernym.integrationtests.e2e.env.Executor.{MockConsumerEdgeAgentApiExecutor, MockEnterpriseEdgeAgentApiExecutor}
import com.evernym.integrationtests.e2e.env.VerityInstance
import com.evernym.integrationtests.e2e.flow.SetupFlow
import com.evernym.integrationtests.e2e.msg.MsgMap
import com.evernym.integrationtests.e2e.scenario.{ApplicationAdminExt, Scenario}
import com.evernym.integrationtests.e2e.util.HttpListenerUtil
import com.evernym.verity.util2.Status._
import com.evernym.verity.actor.agent.MsgPackFormat.{MPF_INDY_PACK, MPF_MSG_PACK}
import com.evernym.verity.actor.agent.msghandler.outgoing.FwdMsg
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.actor.testkit.{CommonSpecUtil, HasActorSystem, TestAppConfig}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.msgfamily.pairwise._
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.constants.Constants._
import com.evernym.verity.did.didcomm.v1.messages.MsgId
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.http.common.models.StatusDetailResp
import com.evernym.verity.protocol.engine.Constants.MTV_1_0
import com.evernym.verity.protocol.protocols.connecting.common.InviteDetail
import com.evernym.verity.testkit.agentmsg._
import com.evernym.verity.testkit.util.AssertionUtil.expectMsgType
import com.evernym.verity.testkit.util._
import com.evernym.verity.testkit.util.http_listener.{PackedMsgHttpListener, PushNotifMsgHttpListener}
import com.evernym.verity.testkit.{AwaitResult, BasicSpecWithIndyCleanup, CancelGloballyAfterFailure}
import com.evernym.verity.util.TimeZoneUtil.getCurrentUTCZonedDateTime
import com.evernym.verity.util._
import com.evernym.verity.util2.{ExecutionContextProvider, UrlParam}
import com.evernym.verity.vault.KeyParam
import org.json.JSONObject
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time._

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.util.Random


trait LegacyApiFlowBaseSpec
  extends BasicSpecWithIndyCleanup
    with Eventually
    with TempDir
    with IntegrationEnv
    with CommonSpecUtil
    with SetupFlow
    with ScalaFutures
    with HttpListenerUtil
    with CancelGloballyAfterFailure
    with AwaitResult{

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(25, Seconds), interval = Span(1, Seconds))

  override lazy val appConfig: AppConfig = new TestAppConfig

  def system: ActorSystem = ActorSystemVanilla(UUID.randomUUID().toString)

  lazy val ledgerUtil = new LedgerUtil(
    appConfig,
    None,
    executionContextProvider.futureExecutionContext,
    taa = ConfigUtil.findTAAConfig(appConfig, "1.0.0"),
    genesisTxnPath = Some(testEnv.ledgerConfig.genesisFilePath),
    system = system
  )

  val edgeHttpEndpointForPackedMsg: PackedMsgHttpListener = {
    val edgeAgent= testEnv.sdk_!("eas-edge-agent")
    new EdgeHttpListenerForPackedMsg(appConfig, edgeAgent.endpoint.get, executionContextProvider.futureExecutionContext)
  }

  val edgeHttpEndpointForPushNotif: PushNotifMsgHttpListener = {
    new EdgeHttpListenerForPushNotifMsg(appConfig, UrlParam("localhost:3456/json-msg"), executionContextProvider.futureExecutionContext)
  }

  val edgeHttpEndpointForSponsors: PushNotifMsgHttpListener = edgeHttpEndpointForPushNotif

  val sendInviteToPhoneNo: Option[String] = Option(phoneNo)

  //these are just client side uids to reference each msgs without knowing underlying uid
  val CLIENT_MSG_UID_CONN_REQ_1 = "connReq1"
  val CLIENT_MSG_UID_CONN_REQ_ANSWER_1 = "connReqAnswer1"
  val CLIENT_MSG_UID_CRED_OFFER_1 = "credOffer1"
  val CLIENT_MSG_UID_CRED_REQ_1 = "credReq1"
  val CLIENT_MSG_UID_CRED_1 = "cred1"
  val CLIENT_MSG_UID_PROOF_REQ_1 = "proofReq1"
  val CLIENT_MSG_UID_PROOF_1 = "proof1"
  val CLIENT_MSG_UID_PROOF_REQ_2 = "proofReq2"

  val entName: String = edgeAgentName + " (integration-tests)"

  def withPreCheck(testCode: => Unit)(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
    if (scenario.restartVerityRandomly) {
      val random = Random.nextInt(100)
      val isEven = (random % 2) == 0
      if (isEven) {
        env.startEnv(testEnv, restart = true)
        aae.consumerAgencyAdmin.checkIfListening()
        aae.enterpriseAgencyAdmin.checkIfListening()
      }
    }
    testCode
  }

  def waitForMsgToBeDelivered(millsToWait: Option[Long] = None): Unit = {
    //waiting enough to make sure msg is delivered by that time (reaches to the target)
    val millisToSleep = millsToWait.getOrElse(TestConstants.defaultWaitTime)
    logApiStart(s"sleep for $millisToSleep millis for msg delivery (if any)...")
    Thread.sleep(millisToSleep)
    logApiFinish("sleep finished")
  }

  def bootstrapAgency(did: String, verKey: String): Unit = {
    ledgerUtil.bootstrapNewDID(did, verKey)
  }

  def logApiStart(msg: String): Unit = {
    logger.info(s"[START] $msg   ($getCurrentUTCZonedDateTime)")
  }

  def logApiFinish(msg: String): Unit = {
    logger.info(s"[FINISH] $msg")
  }

  case class ClientEnvironment (scenario: Scenario,
                                consumerAgencyEndpoint: UrlParam,
                                enterpriseAgencyEndpoint: UrlParam) {
    val enterprise = new EntAgentOwner(scenario, enterpriseAgencyEndpoint)
    val user = new UserAgentOwner(scenario, consumerAgencyEndpoint)
    enterprise.setRemoteConnEdgeOwner(user)
    user.setRemoteConnEdgeOwner(enterprise)
  }

  object GetMsgExpectedDetails {

    def buildToCheckLastSentMsg(totalMsgs: Int, deliveryDetailSize: Int=1): GetMsgExpectedDetails = {
      GetMsgExpectedDetails(totalMsgs, checkForLastSentMsg = true,
        checkForLastReceivedMsg = false, expectedDeliveryDetailSize = deliveryDetailSize, expectingReplyToClientMsgId = None)
    }
    def buildToCheckLastReceivedMsg(expectingReplyToReqMsgUid: Option[String],
                                    totalMsgs: Int, deliveryDetailSize: Int=1): GetMsgExpectedDetails = {
      GetMsgExpectedDetails(totalMsgs, checkForLastSentMsg = false,
        checkForLastReceivedMsg = true, expectedDeliveryDetailSize = deliveryDetailSize,
        expectingReplyToClientMsgId = expectingReplyToReqMsgUid)
    }
  }

  case class GetMsgExpectedDetails(totalMsgs: Int,
                                   checkForLastSentMsg: Boolean,
                                   checkForLastReceivedMsg: Boolean,
                                   expectedDeliveryDetailSize: Int,
                                   expectingReplyToClientMsgId: Option[String]) {
    require(! (checkForLastSentMsg && checkForLastReceivedMsg),
      "checkForLastSentMsg and checkForLastReceivedMsg both can't be set to true")
  }

  trait LegacyAgentOwnerCommon extends ApiClientCommon with MsgMap {  this: AgentMsgSenderHttpWrapper =>
    var remoteConnEdgeOwner: LegacyAgentOwnerCommon = _   //this is owner at the other end of connection
    var lastSentMsgIdByConnId: Map[String, String] = Map.empty


    def getRemoteConnEdgeOwnerMsgSenderDID(connId: String): String =
      remoteConnEdgeOwner.getMsgSenderDID(connId)

    def setRemoteConnEdgeOwner(ao: LegacyAgentOwnerCommon): Unit = remoteConnEdgeOwner = ao

    def getInviteFromRemoteConnEdgeOwner(connId: String): InviteDetail =
      remoteConnEdgeOwner.getPairwiseConnDetail(connId).lastSentInvite

    def checkExpectedMsgFromEdgeEndpoint(hint: String, totalExpectedMsgs: Int = 1): Unit = {
      s"when tried to get message from endpoint ($hint)" - {
        "should be able to get it" in {
          eventually {
            totalExpectedMsgs shouldBe edgeHttpEndpointForPackedMsg.msgCount
          }
          edgeHttpEndpointForPackedMsg.getAndResetReceivedMsgs
        }
      }
    }

    def fetchAgencyIdentity(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      "when sent fetch agency detail" - {
        "should be able to fetch agency detail" in withPreCheck {
          fetchAgencyKey()
        }
      }
    }

    def connectWithAgency(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      "when sent connect msg" - {
        "should be able to connect" in withPreCheck {
          val cr = sendConnectWithAgency()
        }
      }
    }

    def connectAgencyFailsOldProtocol(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when sent connect and a sponsor is required" - {
        "should respond with an error" in withPreCheck {
          val sdr = expectMsgType[StatusDetailResp](sendConnectWithAgency())
          sdr.statusMsg shouldBe PROVISIONING_PROTOCOL_DEPRECATED.statusMsg
        }
      }
    }

    def createAgentFailsOldProtocol_MFV_0_6(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when a sponsor is required" - {
        "should respond with an error" in withPreCheck {
          val sdr = expectMsgType[StatusDetailResp](sendCreateAgentDeprecated_MFV_0_6())
          sdr.statusMsg shouldBe PROVISIONING_PROTOCOL_DEPRECATED.statusMsg
        }
      }
    }

    def createKey_MFV_0_6(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      "when sent create key msg" - {
        "should respond with key created" in withPreCheck {
          val cr = sendConnectCreateKey_MFV_0_6()
        }
      }
    }

    def connReq_MFV_0_6(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      "when sent connection request" - {
        "should respond with connection detail" in withPreCheck {
          val cr = sendConnReq_MFV_0_6()
        }
      }
    }

    def createAgent_MFV_0_6(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      "when sent create agent msg" - {
        "should be able to successfully create agent" in  withPreCheck {
          val acr = sendCreateAgent_MFV_0_6()
        }
      }
    }

    def getToken(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      "when sent get provision token" - {
        "should be able to successfully get token" in withPreCheck {
          val id = "my-id"
          val sponsorId = "sponsor-token"
          val token = sendGetToken(id, sponsorId, edgeHttpEndpointForPushNotif.listeningUrl)
          logger.debug(s"provision token: $token")
          token.sponseeId shouldBe id
        }
      }
    }

    def createAgent_MFV_0_7(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      "when sent create agent msg (V0.7)" - {
        "should be able to successfully create agent" in withPreCheck {
          sendCreateAgent_MFV_0_7()
        }
      }
    }

    def receiveFwdMsgForSponsor(msgSending: () => Unit)(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      "when cloud agent receives a msg and has registered a fwd com method a msg" - {
        "should be forwarded via http to specified endpoint" in withPreCheck {
          val latestFwdMsg = withLatestPushMessage(edgeHttpEndpointForSponsors, {
            msgSending()
          })
          val fwdMsg: FwdMsg = DefaultMsgCodec.fromJson[FwdMsg](latestFwdMsg)
          logger.debug("latestFwdMsg: " + latestFwdMsg)
          logger.debug(s"fwdMsg: $fwdMsg")
          fwdMsg.sponseeDetails shouldBe "FCM::FwdIntegration"
        }
      }
    }

    def createAgentFailures_MFV_0_7(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      "when sent incorrect create agent msg (V0.7)" - {
        "should get problem report" in withPreCheck {
          val acr = sendCreateAgentFailures_MFV_0_7()
        }
      }
    }

    def signupWithAgency(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      "when sent signup msg" - {
        "should be able to signup" in withPreCheck {
          val sr = registerWithAgency()
        }
      }
    }

    def createAgent(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      "when sent create agent msg" - {
        "should be able to successfully create agent" in withPreCheck {
          val acr = sendCreateAgent()
        }
      }
    }

    def createNewKey_0_5(connId: String)(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when sent create key msg (for $connId)" - {
        "should be able to successfully create key for new connection" in withPreCheck {
          val ckr = createPairwiseKey_MFV_0_5(connId)
        }
      }
    }

    def createNewKey_MFV_0_6(connId: String)(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when sent create key msg (for $connId)" - {
        "should be able to successfully create key for new connection" in withPreCheck {
          val ckr = createPairwiseKey_MFV_0_6(connId)
        }
      }
    }

    def queryMetrics()(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when keys are created" - {
        "should be able to successfully query metrics" in withPreCheck {
          if (!scenario.restartVerityRandomly) {
            //metrics are reset on restart, only test when not restarted
            getAllNodeMetrics(aae.consumerAgencyAdmin.metricsHost)
            getAllNodeMetrics(aae.enterpriseAgencyAdmin.metricsHost)
          }
        }
      }
    }

    def sendInvitationBeforeSettingReqConfigs(connId: String)
                                             (implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when sent create and send invite msg before setting required configs (for $connId)" - {
        "should be able to successfully create invite msg" in {
          val sdr = expectMsgType[StatusDetailResp](sendInviteForConn(connId))
          sdr shouldBe StatusDetailResp(DATA_NOT_FOUND.statusCode, s"required configs not yet set: $NAME_KEY, $LOGO_URL_KEY", None)
        }
      }
    }

    def updateAgentConfig(cds: Set[TestConfigDetail])
                         (implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      "when sent update agent config" - {
        "should be able to successfully update configs" in withPreCheck {
          val cur = sendUpdateAgentConfig(cds)
        }
      }
    }

    def updateAgentComMethod(cm: TestComMethod)(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when sent update agent com method (${cm.value})" - {
        "should be able to successfully update com method" in withPreCheck {
          val cur = sendUpdateAgentComMethod(cm)
        }
      }
    }

    def updateConnStatus(connId: String, statusCode: String)
                        (implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      implicit val msgPackagingContext: AgentMsgPackagingContext =
        AgentMsgPackagingContext(MPF_MSG_PACK, MTV_1_0, packForAgencyRoute = true)
      s"when sent update connection status (for $connId)" - {
        "should be able to successfully update connection status" in withPreCheck {
          val cur = sendUpdateConnStatus_MFV_0_5(connId, statusCode)
        }
      }
    }

    def setupPublicIdentifier()(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      "when sent setup issuer create msg" - {
        "should be able to successfully create public identifier" in {
          createPublicIdentifier()

          val response = eventually (timeout(Span(10, Seconds)), interval(Span(3, Seconds))) {
            val latestMsgOpt = edgeHttpEndpointForPackedMsg.getAndResetReceivedMsgs.headOption
            latestMsgOpt.isDefined shouldBe true
            latestMsgOpt.get
          }
          storePublicIdentifier(response)
        }
      }
    }

    def sendInvitation_MFV_0_5(connId: String, includePublicDID: Boolean = false)(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when sent create and send invite msg after setting required configs (for $connId)" - {
        "should be able to successfully create and send invite msg" in withPreCheck {
          val icr = expectMsgType[CreateInviteResp_MFV_0_5](sendInviteForConn(connId, ph = sendInviteToPhoneNo,
            includePublicDID = includePublicDID))
          if (includePublicDID) {
            icr.md.inviteDetail.senderDetail.publicDID.contains(mockClientAgent.publicIdentifier.get.did) shouldBe true
          }
          addToMsgs(connId, CLIENT_MSG_UID_CONN_REQ_1, MsgBasicDetail(icr.mc.uid, CREATE_MSG_TYPE_CONN_REQ, None))
          sendInviteToPhoneNo.foreach { ph =>
            waitForMsgToBeDelivered(scenario.restartMsgWait)
          }
        }
      }
    }

    def sendInvitation_MFV_0_6(connId: String, includePublicDID: Boolean = false) (implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when sent create and send invite msg after setting required configs (for $connId)" - {
        "should be able to successfully create and send invite msg" in withPreCheck {
          val icr = expectMsgType[ConnReqRespMsg_MFV_0_6](sendInviteForConn_MFV_0_6(connId, ph = sendInviteToPhoneNo,
            includePublicDID = includePublicDID))
          if (includePublicDID) {
            icr.inviteDetail.senderDetail.publicDID.contains(mockClientAgent.publicIdentifier.get.did) shouldBe true
          }
          addToMsgs(connId, CLIENT_MSG_UID_CONN_REQ_1, MsgBasicDetail(icr.inviteDetail.connReqId, CREATE_MSG_TYPE_CONN_REQ, None))
          sendInviteToPhoneNo.foreach { ph =>
            waitForMsgToBeDelivered(scenario.restartMsgWait)
          }
        }
      }
    }

    def sentGetMsgAfterSendingInvitation(connId: String)
                                        (implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when sent get msgs after sending invitation msg (for $connId)" - {
        "should be able to get one msg" in withPreCheck {

          eventually (timeout(Span(10, Seconds)), interval(Span(2, Seconds))) {
            val gmr = expectMsgType[Msgs_MFV_0_5](getMsgsFromConn_MPV_0_5(connId))
            gmr.msgs.size shouldBe 1
            val crm = gmr.msgs.find(_.uid == getMsgUidReq(connId, CLIENT_MSG_UID_CONN_REQ_1)).get
            crm.senderDID shouldBe getMsgSenderDID(connId)
            sendInviteToPhoneNo match {
              case Some(_) =>
                //TODO: need to finalize if we want to test this
                //crm.statusCode shouldBe SC_MSG_STATUS_SENT
              case None =>
                crm.statusCode shouldBe MSG_STATUS_CREATED.statusCode
            }
          }
        }
      }
    }

    def answerInvitation(connId: String)(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when sent answer invite msg (for $connId)" - {
        "should be able to successfully accept connection req" in withPreCheck {
          val invite = getInviteFromRemoteConnEdgeOwner(connId)
          val pcd = getPairwiseConnDetail(connId)
          pcd.setTheirPairwiseDidPair(invite.senderDetail.DID, invite.senderDetail.verKey)
          val iar = expectMsgType[InviteAcceptedResp_MFV_0_5](answerInviteForConn(connId, invite))
          addToMsgs(connId, CLIENT_MSG_UID_CONN_REQ_ANSWER_1,
            MsgBasicDetail(iar.mc.uid, CREATE_MSG_TYPE_CONN_REQ_ANSWER, Option(invite.connReqId)))
          waitForMsgToBeDelivered(scenario.restartMsgWait)
        }
      }
    }

    def redirectInvitation_0_5(oldConnId: String, connId: String)(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when sent redirect invite msg (for $connId)" - {
        "should be able to successfully redirect connection req" in withPreCheck {
          val invite = getInviteFromRemoteConnEdgeOwner(connId)
          val iar = expectMsgType[MsgCreated_MFV_0_5](redirectConnReq_MFV_0_5(oldConnId, connId, invite))
          waitForMsgToBeDelivered(scenario.restartMsgWait)
        }
      }
    }

    def redirectInvitation_0_6(oldConnId: String, connId: String)(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when sent redirect invite msg (for $connId)" - {
        "should be able to successfully redirect connection req" in withPreCheck {
          val invite = getInviteFromRemoteConnEdgeOwner(connId)
          val iar = expectMsgType[ConnReqRedirectResp_MFV_0_6](redirectConnReq_MFV_0_6(oldConnId, connId, invite))
          waitForMsgToBeDelivered(scenario.restartMsgWait)
        }
      }
    }

    def acceptInvitation_MFV_0_6(connId: String)(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when sent answer invite msg (for $connId)" - {
        "should be able to successfully accept connection req" in withPreCheck {
          val invite = getInviteFromRemoteConnEdgeOwner(connId)
          val iar = expectMsgType[ConnReqAccepted_MFV_0_6](acceptInviteForConn_MFV_0_6(connId, invite, alreadyAccepted = false))
          addToMsgs(connId, CLIENT_MSG_UID_CONN_REQ_ANSWER_1,
            MsgBasicDetail(iar.`@id`, CREATE_MSG_TYPE_CONN_REQ_ANSWER, Option(invite.connReqId)))
          waitForMsgToBeDelivered(scenario.restartMsgWait)
        }
      }
    }

    def tryToAnswerSameInvitationAgain(connId: String)
                                      (implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when sent answer invite msg again (for $connId)" - {
        "should respond with an error" in withPreCheck {
          val sdr = expectMsgType[StatusDetailResp](answerInviteForConn(connId,
            getInviteFromRemoteConnEdgeOwner(connId)))
          sdr shouldBe StatusDetailResp(ACCEPTED_CONN_REQ_EXISTS.statusCode, ACCEPTED_CONN_REQ_EXISTS.statusMsg, None)
        }
      }
    }

    def tryToAnswerSameInvitationAgain_MFV_0_6(connId: String)
                                      (implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when sent answer invite msg again (for $connId)" - {
        "should respond with an error" in withPreCheck {
          val sdr = expectMsgType[StatusDetailResp](acceptInviteForConn_MFV_0_6(connId,
            getInviteFromRemoteConnEdgeOwner(connId), alreadyAccepted = true))
          sdr shouldBe StatusDetailResp(ACCEPTED_CONN_REQ_EXISTS.statusCode, ACCEPTED_CONN_REQ_EXISTS.statusMsg, None)
        }
      }
    }

    def sentGetMsgAfterAnsweringInvitation(connId: String)
                                          (implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when sent get msgs after accepting invitation (for $connId)" - {
        "should be able to get two msgs" in withPreCheck {
          eventually (timeout(Span(10, Seconds)), interval(Span(2, Seconds))) {
            val gmr = expectMsgType[Msgs_MFV_0_5](getMsgsFromConn_MPV_0_5(connId))
            gmr.msgs.size shouldBe 2
            val crUid = remoteConnEdgeOwner.getMsgUidReq(connId, CLIENT_MSG_UID_CONN_REQ_1)
            val craUid = getMsgUidReq(connId, CLIENT_MSG_UID_CONN_REQ_ANSWER_1)
            val cam = gmr.msgs.find(_.uid == craUid).get
            val crm = gmr.msgs.find(_.uid == crUid).get

            crm.senderDID shouldBe getInviteFromRemoteConnEdgeOwner(connId).senderDetail.DID
            crm.refMsgId.contains(craUid) shouldBe true
            crm.statusCode shouldBe MSG_STATUS_ACCEPTED.statusCode

            cam.senderDID shouldBe getMsgSenderDID(connId)
            cam.statusCode shouldBe MSG_STATUS_ACCEPTED.statusCode
          }
        }
      }
    }

    def sendGetMsgAfterItIsAccepted(connId: String,
                                    expectedMsgType: String,
                                    expectedMsgStatus: String = MSG_STATUS_ACCEPTED.statusCode,
                                    check: String => Unit = { _ => })
                                   (implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when sent get msgs after user accepted invitation (for $connId)" - {
        "should be able to get two msgs" in withPreCheck {
          eventually(timeout(Span(10, Seconds)), interval(Span(3, Seconds))) {
            val gmr = expectMsgType[Msgs_MFV_0_5](getMsgsFromConn_MPV_0_5(connId))
            gmr.msgs.size shouldBe 2
            val crMsg = getMsgReq(connId, CLIENT_MSG_UID_CONN_REQ_1)
            val cam = gmr.msgs.find(_.`type` == expectedMsgType).get
            addToMsgs(connId, CLIENT_MSG_UID_CONN_REQ_ANSWER_1,
              MsgBasicDetail(cam.uid, expectedMsgType, Option(crMsg.uid)))

            val crm = gmr.msgs.find(_.uid == crMsg.uid).get
            crm.refMsgId.contains(cam.uid) shouldBe true
            crm.statusCode shouldBe expectedMsgStatus

            cam.senderDID shouldBe getRemoteConnEdgeOwnerMsgSenderDID(connId)
            cam.statusCode shouldBe expectedMsgStatus

            val unsealKeyParam = KeyParam.fromDID(mockClientAgent.getDIDToUnsealAgentRespMsg)
            val amw = convertToSyncReq(mockClientAgent.agentMsgTransformer.unpackAsync(cam.payload.get, unsealKeyParam)(mockClientAgent.wap))

            val respJsonMsg = amw.msgPackFormat match {
              case MPF_MSG_PACK =>
                val unpackedPayloadMsg = amw.headAgentMsg.convertTo[PayloadMsg_MFV_0_5]
                unpackedPayloadMsg.`@type` shouldBe TypeDetail(expectedMsgType, MTV_1_0, Option(PACKAGING_FORMAT_INDY_MSG_PACK))
                val jsonMsg = MessagePackUtil.convertPackedMsgToJsonString(unpackedPayloadMsg.`@msg`)
                expectedMsgType match {
                  case MSG_TYPE_CONN_REQ_ACCEPTED | CREATE_MSG_TYPE_CONN_REQ_ANSWER => DefaultMsgCodec.fromJson[InviteAnswerPayloadMsg](jsonMsg)
                  case CREATE_MSG_TYPE_CONN_REQ_REDIRECTED => DefaultMsgCodec.fromJson[RedirectPayloadMsg_0_5](jsonMsg)
                }
                jsonMsg
              case MPF_INDY_PACK =>
                val fullExpectedMsgType = expectedMsgType match {
                  case MSG_TYPE_CONN_REQ_ACCEPTED => MSG_TYPE_DETAIL_CONN_REQ_ACCEPTED
                  case MSG_TYPE_CONN_REQ_REDIRECTED => MSG_TYPE_DETAIL_CONN_REQ_REDIRECTED
                }
                val unpackedPayloadMsg = amw.headAgentMsg.convertTo[PayloadMsg_MFV_0_6]
                unpackedPayloadMsg.`@type` shouldBe fullExpectedMsgType
                unpackedPayloadMsg.`@msg`.toString()
              case _ => throw new Exception(s"Unknown msgPackFormat -- ${amw.msgPackFormat}")
            }
            check(respJsonMsg)
          }
        }
      }
    }

    def sendsNewMsg_MFV_0_5(connId: String, clientMsgUid: MsgId, msgType: String, msg: String,
                            replyToClientMsgUid: Option[String] = None)
                           (implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when sent $msgType msg with clientMsgUid: $clientMsgUid (for $connId)" - {
        "should be able to send it successfully" in withPreCheck {
          val replyToMsgId = replyToClientMsgUid.map(clientMsgUid => getMsgUidReq(connId, clientMsgUid))
          val smr = expectMsgType[GeneralMsgCreatedResp_MFV_0_5](sendGeneralMsgToConn(connId, msgType, msg, replyToMsgId))
          lastSentMsgIdByConnId += (connId -> smr.mc.uid)
          addToMsgs(connId, clientMsgUid, MsgBasicDetail(smr.mc.uid, msgType, replyToMsgId))
          waitForMsgToBeDelivered(scenario.restartMsgWait)
        }
      }
    }

    def sendsNewMsgForFwd(connId: String, clientMsgUid: MsgId, msgType: String, msg: String,
                replyToClientMsgUid: Option[String] = None): Unit = {
      val replyToMsgId = replyToClientMsgUid.map(clientMsgUid => getMsgUidReq(connId, clientMsgUid))
      val smr = expectMsgType[RemoteMsgSent_MFV_0_6](sendGeneralMsgToConn_MFV_0_6(connId, msgType, msg, replyToMsgId))
      lastSentMsgIdByConnId += (connId -> smr.`@id`)
      addToMsgs(connId, clientMsgUid, MsgBasicDetail(smr.`@id`, msgType, replyToMsgId))
      waitForMsgToBeDelivered(Some(TestConstants.defaultWaitTime))
    }

    def sendsNewMsg_MFV_0_6(connId: String, clientMsgUid: MsgId, msgType: String, msg: String,
                            replyToClientMsgUid: Option[String] = None)
                           (implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when sent $msgType msg with clientMsgUid: $clientMsgUid (for $connId)" - {
        "should be able to send it successfully" in withPreCheck {
          val replyToMsgId = replyToClientMsgUid.map(clientMsgUid => getMsgUidReq(connId, clientMsgUid))
          val smr = expectMsgType[RemoteMsgSent_MFV_0_6](sendGeneralMsgToConn_MFV_0_6(connId, msgType, msg, replyToMsgId))
          lastSentMsgIdByConnId += (connId -> smr.`@id`)
          addToMsgs(connId, clientMsgUid, MsgBasicDetail(smr.`@id`, msgType, replyToMsgId))
          waitForMsgToBeDelivered(scenario.restartMsgWait)
        }
      }
    }

    def sendGetMsgsByConn(connId: String, clientMsgUid: MsgId, gme: GetMsgExpectedDetails)
                         (implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when sent get msgs after sending/receiving $clientMsgUid (for $connId)" - {
        "should be able to get that msg" in withPreCheck {
          eventually (timeout(Span(10, Seconds)), interval(Span(2, Seconds))) {
            val gmr = expectMsgType[Msgs_MFV_0_5](getMsgsFromConn_MPV_0_5(connId))
            gmr.msgs.size shouldBe gme.totalMsgs

            if (gme.checkForLastReceivedMsg || gme.checkForLastSentMsg) {
              val (lastMsgDetail, senderDID, statusCode) =
                if (gme.checkForLastSentMsg)
                  (msgsByConns(connId).values.find(_.uid == lastSentMsgIdByConnId(connId)).orNull,
                    getMsgSenderDID(connId), MSG_STATUS_SENT.statusCode)
                else
                  (remoteConnEdgeOwner.msgsByConns(connId).values.
                    find(_.uid == remoteConnEdgeOwner.lastSentMsgIdByConnId(connId)).orNull,
                    getRemoteConnEdgeOwnerMsgSenderDID(connId), MSG_STATUS_RECEIVED.statusCode)

              val newMsg = gmr.msgs.find(_.uid == lastMsgDetail.uid).get
              lastMsgDetail.replyToMsgId.foreach { replyToMsgId =>
                val replyToMsg = gmr.msgs.find(_.uid == replyToMsgId).get
                replyToMsg.refMsgId.contains(lastMsgDetail.uid) shouldBe true
              }

              newMsg.senderDID shouldBe senderDID
              newMsg.statusCode shouldBe statusCode

              if (gme.checkForLastReceivedMsg) {
                gme.expectingReplyToClientMsgId.foreach { reqClientMsgId =>
                  val connMsgs = msgsByConns(connId)
                  val reqMsgUid = connMsgs(reqClientMsgId).uid
                  gmr.msgs.find(_.uid == reqMsgUid).get.refMsgId.isDefined shouldBe true
                }
              }
              addToMsgs(connId, clientMsgUid, MsgBasicDetail(newMsg.uid, newMsg.`type`, newMsg.refMsgId))
            }
          }
        }
      }
    }

    def sendGetMsgsByConns(hint: String, totalExpectedConnsForMsgs: Int, connsIds: Option[List[String]] = None)
                         (implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when sent get msgs by conns ($hint)" - {
        "should be able to get msgs by conns" in withPreCheck {
          implicit val msgPackagingContext: AgentMsgPackagingContext =
            AgentMsgPackagingContext(MPF_MSG_PACK, MTV_1_0, packForAgencyRoute = true)
          eventually (timeout(Span(10, Seconds)), interval(Span(2, Seconds))) {
            val pairwiseDIDs = connsIds.map { cids =>
              mockClientAgent.pairwiseConnDetails.filter(cids.contains).values.map(_.myPairwiseDidPair.did).toList
            }

            val gmr = expectMsgType[MsgsByConns_MFV_0_5](getMsgsFromConns_MPV_0_5(pairwiseDIDs))
            gmr.msgsByConns.size shouldBe totalExpectedConnsForMsgs
          }
        }
      }
    }

    def sendGetMsgAndCheckStatus(connId: String, clientMsgUid: MsgId, status: String)
                                (implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when sent get msgs to check status for $clientMsgUid (for $connId)" - {
        "should be able to get that msg" in withPreCheck {
          val uid = getMsgUidReq(connId, clientMsgUid)
          val gmr = expectMsgType[Msgs_MFV_0_5](getMsgsFromConn_MPV_0_5(connId))
          val msg = gmr.msgs.find(_.uid == uid).orNull
          msg.statusCode shouldBe status
        }
      }
    }

    def updateMsgStatus(connId: String, clientMsgUid: MsgId, statusCode: String)
                       (implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      s"when sent update msg status msg (for $connId)" - {
        "should be able to update it successfully" in withPreCheck {
          implicit val msgPackagingContext: AgentMsgPackagingContext =
            AgentMsgPackagingContext(MPF_MSG_PACK, MTV_1_0, packForAgencyRoute = true)
          val msgUid = getMsgUidReq(connId, clientMsgUid)
          val umr = updateMsgStatusForConn_MFV_0_5(connId, uids = List(msgUid), statusCode = statusCode)
        }
      }
    }

    def updateMsgStatusByConns(hint: String, statusCode: String, connIds: Set[String])
                       (implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      implicit val msgPackagingContext: AgentMsgPackagingContext =
        AgentMsgPackagingContext(MPF_MSG_PACK, MTV_1_0, packForAgencyRoute = true)
      s"when sent update msg status by conns msg ($hint)" - {
        "should be able to update it successfully" in withPreCheck {
          val msgUidsByPairwiseDIDs = connIds.map { connId =>
            val con = mockClientAgent.pairwiseConnDetail(connId)
            val connMsgUids = msgsByConns(connId).map(_._2.uid).toList
            PairwiseMsgUids(con.myPairwiseDidPair.did, connMsgUids)
          }.toList
          val umr = updateMsgStatusByConns(statusCode, msgUidsByPairwiseDIDs)
        }
      }
    }

    def setupTillAgentCreation(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      fetchAgencyIdentity
      connectWithAgency
      signupWithAgency
      createAgent
    }

    def setupTillAgentCreation_MFV_0_6(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      fetchAgencyIdentity
      createKey_MFV_0_6
      connReq_MFV_0_6
      createAgent_MFV_0_6
    }

    def setupTillAgentCreation_MFV_0_7(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      fetchAgencyIdentity
      getToken
      createKey_MFV_0_6
      connReq_MFV_0_6
      //TODO: This needs to be turned back on when 0.5 and 0.6 are removed and RequireSponsorFlowSpec is deleted
//      createAgentFailures_MFV_0_7
      createAgent_MFV_0_7
    }

    def setupTillAgentCreationFailsDeprecated(implicit scenario: Scenario, aae: AgencyAdminEnvironment): Unit = {
      fetchAgencyIdentity
      connectAgencyFailsOldProtocol
      createKey_MFV_0_6
      connReq_MFV_0_6
      createAgentFailsOldProtocol_MFV_0_6
    }
  }

  class EntAgentOwner(val scenario: Scenario, override val urlParam: UrlParam)
    extends MockEnterpriseEdgeAgentApiExecutor(urlParam)
      with LegacyAgentOwnerCommon

  class UserAgentOwner(val scenario: Scenario, override val urlParam: UrlParam)
    extends MockConsumerEdgeAgentApiExecutor(urlParam)
      with LegacyAgentOwnerCommon

  //----------------------------------------------------------------------


  def setupAgency(ae: AgencyAdminEnvironment): Unit = {
    implicit def sc: Scenario = ae.scenario
    s"${ae.scenario.name}" - {
      "Consumer Agency Admin" - {
        setupApplication(ae.consumerAgencyAdmin, ledgerUtil)
      }

      "Enterprise Agency Admin" - {
        setupApplication(ae.enterpriseAgencyAdmin, ledgerUtil)
      }
    }
  }

  def generalEndToEndFlowScenario(ce: ClientEnvironment)(implicit aae: AgencyAdminEnvironment): Unit = {
    implicit def sc: Scenario = ce.scenario

    s"${ce.scenario.name}" - {

      "Enterprise" - {
        ce.enterprise.setupTillAgentCreation
        ce.enterprise.updateAgentComMethod(TestComMethod("1", COM_METHOD_TYPE_HTTP_ENDPOINT,
          Option(s"${edgeHttpEndpointForPackedMsg.listeningUrl}")))
        ce.enterprise.updateAgentComMethod(TestComMethod("2", COM_METHOD_TYPE_PUSH,
          Option(s"${edgeHttpEndpointForPushNotif.listeningUrl}")))
        ce.enterprise.setupPublicIdentifier()
        ce.scenario.connIds.foreach(ce.enterprise.createNewKey_0_5)
        if (aae.easVerityInstance.setup) {
          //this is conditional because metrics are exposed on internal api which is only allowed from internal network
          ce.enterprise.queryMetrics()
        }
        //TODO: need to finalize about need of this test case
        //ce.scenario.connIds.foreach(ce.enterprise.sendInvitationBeforeSettingReqConfigs)
        ce.enterprise.updateAgentConfig(Set(TestConfigDetail(NAME_KEY, Option(entName)),
          TestConfigDetail(LOGO_URL_KEY, Option(edgeAgentLogoUrl))))
        ce.scenario.connIds.foreach(conId => ce.enterprise.sendInvitation_MFV_0_5(conId, includePublicDID = true))
        ce.scenario.connIds.foreach(ce.enterprise.sentGetMsgAfterSendingInvitation)
        ce.enterprise.sendGetMsgsByConns("after sending invitation", 2)
        ce.enterprise.checkExpectedMsgFromEdgeEndpoint("no msg expected", 0)
      }

      "User" - {
        ce.user.setupTillAgentCreation
        ce.user.updateAgentComMethod(TestComMethod("1", COM_METHOD_TYPE_PUSH, Option("FCM:test-123")))
        ce.scenario.connIds.foreach(ce.user.createNewKey_0_5)
        ce.scenario.connIds.foreach(ce.user.answerInvitation)
        ce.scenario.connIds.foreach(ce.user.sentGetMsgAfterAnsweringInvitation)
        ce.user.sendGetMsgsByConns("after answering invitation", 2)
        //ce.scenario.connIds.foreach(ce.user.tryToAnswerSameInvitationAgain)
      }

      "Enterprise" - {
        ce.enterprise.checkExpectedMsgFromEdgeEndpoint("answer message expected", ce.scenario.connIds.size)
        ce.scenario.connIds.foreach(ce.enterprise.sendGetMsgAfterItIsAccepted(_, CREATE_MSG_TYPE_CONN_REQ_ANSWER))
        ce.enterprise.sendGetMsgsByConns("after invitation accepted", 2)
        ce.scenario.connIds.foreach( connId =>
          ce.enterprise.sendsNewMsg_MFV_0_5( connId, CLIENT_MSG_UID_CRED_OFFER_1, CREATE_MSG_TYPE_CRED_OFFER, "cred offer msg"))
        ce.scenario.connIds.foreach( connId =>
          ce.enterprise.sendGetMsgsByConn(connId, CLIENT_MSG_UID_CRED_OFFER_1, GetMsgExpectedDetails.buildToCheckLastSentMsg(3)))
      }

      "User" - {
        ce.scenario.connIds.foreach( connId =>
          ce.user.sendGetMsgsByConn(connId, CLIENT_MSG_UID_CRED_OFFER_1, GetMsgExpectedDetails.buildToCheckLastReceivedMsg(None, 3)))
          ce.user.updateMsgStatusByConns("mark cred offer msg as reviewed", MSG_STATUS_REVIEWED.statusCode, ce.scenario.connIds)
          ce.scenario.connIds.foreach( connId =>
          ce.user.sendsNewMsg_MFV_0_5(connId, CLIENT_MSG_UID_CRED_REQ_1, CREATE_MSG_TYPE_CRED_REQ,
          "cred request msg", Option(CLIENT_MSG_UID_CRED_OFFER_1)))
        ce.scenario.connIds.foreach( connId =>
          ce.user.sendGetMsgsByConn(connId, CLIENT_MSG_UID_CRED_REQ_1,
            GetMsgExpectedDetails.buildToCheckLastSentMsg(4, deliveryDetailSize = 2)))
      }

      "Enterprise" - {
        ce.enterprise.checkExpectedMsgFromEdgeEndpoint("cred request msg expected", ce.scenario.connIds.size)
        ce.scenario.connIds.foreach( connId =>
          ce.enterprise.sendGetMsgsByConn(connId, CLIENT_MSG_UID_CRED_REQ_1,
            GetMsgExpectedDetails.buildToCheckLastReceivedMsg(Option(CLIENT_MSG_UID_CRED_OFFER_1), totalMsgs = 4, deliveryDetailSize = 0)))
        ce.scenario.connIds.foreach( connId =>
          ce.enterprise.sendsNewMsg_MFV_0_5(connId, CLIENT_MSG_UID_CRED_1, CREATE_MSG_TYPE_CRED,
          "cred msg", Option(CLIENT_MSG_UID_CRED_REQ_1)))
        ce.scenario.connIds.foreach( connId =>
          ce.enterprise.sendGetMsgsByConn(connId, CLIENT_MSG_UID_CRED_1, GetMsgExpectedDetails.buildToCheckLastSentMsg(5)))
      }

      "User" - {
        ce.scenario.connIds.foreach( connId =>
          ce.user.sendGetMsgsByConn(connId, CLIENT_MSG_UID_CRED_1,
            GetMsgExpectedDetails.buildToCheckLastReceivedMsg(Option(CLIENT_MSG_UID_CRED_REQ_1), 5)))
        ce.scenario.connIds.foreach( connId =>
          ce.user.updateMsgStatus(connId, CLIENT_MSG_UID_CRED_1, MSG_STATUS_ACCEPTED.statusCode))
        ce.scenario.connIds.foreach( connId =>
          ce.user.sendGetMsgAndCheckStatus(connId, CLIENT_MSG_UID_CRED_1, MSG_STATUS_ACCEPTED.statusCode))
      }

      "Enterprise" - {
        ce.scenario.connIds.foreach( connId =>
          ce.enterprise.sendsNewMsg_MFV_0_5(connId, CLIENT_MSG_UID_PROOF_REQ_1, CREATE_MSG_TYPE_PROOF_REQ, "proof request msg"))
        ce.scenario.connIds.foreach( connId =>
          ce.enterprise.sendGetMsgsByConn(connId, CLIENT_MSG_UID_PROOF_REQ_1, GetMsgExpectedDetails.buildToCheckLastSentMsg(6)))
      }

      "User" - {
        ce.scenario.connIds.foreach( connId =>
          ce.user.sendGetMsgsByConn(connId, CLIENT_MSG_UID_PROOF_REQ_1, GetMsgExpectedDetails.buildToCheckLastReceivedMsg(None, 6)))
        ce.scenario.connIds.foreach( connId =>
          ce.user.sendsNewMsg_MFV_0_5(connId, CLIENT_MSG_UID_PROOF_1, CREATE_MSG_TYPE_PROOF,
          "proof msg", Option(CLIENT_MSG_UID_PROOF_REQ_1)))
        ce.scenario.connIds.foreach( connId =>
          ce.user.sendGetMsgsByConn(connId, CLIENT_MSG_UID_PROOF_1, GetMsgExpectedDetails.buildToCheckLastSentMsg(7, deliveryDetailSize = 2)))
      }

      "Enterprise" - {
        ce.enterprise.checkExpectedMsgFromEdgeEndpoint("proof msg expected", ce.scenario.connIds.size)
        ce.scenario.connIds.foreach( connId =>
          ce.enterprise.sendGetMsgsByConn(connId, CLIENT_MSG_UID_PROOF_1,
          GetMsgExpectedDetails.buildToCheckLastReceivedMsg(Option(CLIENT_MSG_UID_PROOF_REQ_1), totalMsgs = 7, deliveryDetailSize = 0)))
        ce.scenario.connIds.foreach( connId =>
          ce.enterprise.updateMsgStatus(connId, CLIENT_MSG_UID_PROOF_1, MSG_STATUS_REVIEWED.statusCode))
        ce.scenario.connIds.foreach( connId =>
          ce.enterprise.sendGetMsgAndCheckStatus(connId, CLIENT_MSG_UID_PROOF_1, MSG_STATUS_REVIEWED.statusCode))
      }

      "User" - {
        ce.scenario.connIds.foreach(connId => ce.user.updateConnStatus(connId, CONN_STATUS_DELETED.statusCode))
      }

      "Enterprise" - {
        ce.scenario.connIds.foreach(connId =>
          ce.enterprise.sendsNewMsg_MFV_0_5(connId, CLIENT_MSG_UID_PROOF_REQ_2, CREATE_MSG_TYPE_PROOF_REQ, "proof request msg 2"))
        ce.scenario.connIds.foreach(connId =>
          ce.enterprise.sendGetMsgsByConn(connId, CLIENT_MSG_UID_PROOF_REQ_2, GetMsgExpectedDetails.buildToCheckLastSentMsg(8)))
      }

      "User" - {
        ce.scenario.connIds.foreach(connId =>
          ce.user.sendGetMsgsByConn(connId, CLIENT_MSG_UID_PROOF_REQ_2,
          GetMsgExpectedDetails.buildToCheckLastReceivedMsg(None, totalMsgs = 8, deliveryDetailSize = 0)))
      }

      val newConnId = "newConn11"
      "Redirecting" - {
        "Enterprise sending invitation" - {
          ce.enterprise.createNewKey_0_5(newConnId)
          ce.enterprise.sendInvitation_MFV_0_5(newConnId, includePublicDID = true)
          ce.enterprise.sendGetMsgsByConns("after sending new invitation", 3)
        }
        "Consumer redirecting invitation" - {
          ce.user.createNewKey_0_5(newConnId)
          ce.user.redirectInvitation_0_5(ce.scenario.connIds.last, newConnId)
        }
        "Enterprise receives conn req redirected" - {
          ce.enterprise.checkExpectedMsgFromEdgeEndpoint("send redirect resp")
          ce.enterprise.sendGetMsgAfterItIsAccepted(newConnId, CREATE_MSG_TYPE_CONN_REQ_REDIRECTED, MSG_STATUS_REDIRECTED.statusCode, checkRedirect)
        }
      }

    }
  }

  def generalEndToEndFlowScenario_MFV_0_6(ce: ClientEnvironment)(implicit aae: AgencyAdminEnvironment): Unit = {
    implicit def sc: Scenario = ce.scenario

    s"${ce.scenario.name}" - {

      "Enterprise" - {
        ce.enterprise.setupTillAgentCreation_MFV_0_7
        ce.enterprise.updateAgentComMethod(TestComMethod("1", COM_METHOD_TYPE_HTTP_ENDPOINT,
          Option(s"${edgeHttpEndpointForPackedMsg.listeningUrl}")))
        ce.enterprise.setupPublicIdentifier()
        ce.scenario.connIds.foreach(ce.enterprise.createNewKey_MFV_0_6)
        ce.enterprise.updateAgentConfig(Set(TestConfigDetail(NAME_KEY, Option(entName)),
          TestConfigDetail(LOGO_URL_KEY, Option(edgeAgentLogoUrl))))
        ce.scenario.connIds.foreach(conId => ce.enterprise.sendInvitation_MFV_0_6(conId, includePublicDID = true))
        ce.scenario.connIds.foreach(ce.enterprise.sentGetMsgAfterSendingInvitation)
        ce.enterprise.sendGetMsgsByConns("after sending invitation", 2)

      }

      "User" - {
        ce.user.setupTillAgentCreation_MFV_0_7
        ce.user.updateAgentComMethod(TestComMethod("1", COM_METHOD_TYPE_PUSH, Option("FCM:test-123")))
        ce.scenario.connIds.foreach(ce.user.createNewKey_MFV_0_6)
        ce.scenario.connIds.foreach(ce.user.acceptInvitation_MFV_0_6)
        ce.scenario.connIds.foreach(ce.user.sentGetMsgAfterAnsweringInvitation)
        ce.user.sendGetMsgsByConns("after answering invitation", 2)
        ce.scenario.connIds.foreach(ce.user.tryToAnswerSameInvitationAgain_MFV_0_6)
      }

      "Enterprise" - {
        ce.enterprise.checkExpectedMsgFromEdgeEndpoint("answer msg expected", ce.scenario.connIds.size)
        ce.scenario.connIds.foreach(ce.enterprise.sendGetMsgAfterItIsAccepted(_, MSG_TYPE_CONN_REQ_ACCEPTED))
        ce.enterprise.sendGetMsgsByConns("after invitation accepted", 2)
        ce.scenario.connIds.foreach( connId =>
          ce.enterprise.sendsNewMsg_MFV_0_6( connId, CLIENT_MSG_UID_CRED_OFFER_1, CREATE_MSG_TYPE_CRED_OFFER, "cred offer msg"))
        ce.scenario.connIds.foreach( connId =>
          ce.enterprise.sendGetMsgsByConn(connId, CLIENT_MSG_UID_CRED_OFFER_1, GetMsgExpectedDetails.buildToCheckLastSentMsg(3)))
      }

      "User" - {
        ce.scenario.connIds.foreach( connId =>
          ce.user.sendGetMsgsByConn(connId, CLIENT_MSG_UID_CRED_OFFER_1, GetMsgExpectedDetails.buildToCheckLastReceivedMsg(None, 3)))
        ce.user.updateMsgStatusByConns("mark cred offer msg as reviewed", MSG_STATUS_REVIEWED.statusCode, ce.scenario.connIds)
        ce.scenario.connIds.foreach( connId =>
          ce.user.sendsNewMsg_MFV_0_6(connId, CLIENT_MSG_UID_CRED_REQ_1, CREATE_MSG_TYPE_CRED_REQ,
            "cred request msg", Option(CLIENT_MSG_UID_CRED_OFFER_1)))
        ce.scenario.connIds.foreach( connId =>
          ce.user.sendGetMsgsByConn(connId, CLIENT_MSG_UID_CRED_REQ_1,
            GetMsgExpectedDetails.buildToCheckLastSentMsg(4, deliveryDetailSize = 2)))

        val msgSender = () =>
          ce.enterprise.sendsNewMsgForFwd(ce.scenario.connIds.head,  CLIENT_MSG_UID_CRED_OFFER_1, CREATE_MSG_TYPE_CRED_OFFER, "cred offer msg")

        ce.user.updateAgentComMethod(TestComMethod("id", COM_METHOD_TYPE_FWD_PUSH, Some("FCM::FwdIntegration")))
        ce.user.receiveFwdMsgForSponsor(msgSender)
      }

      val newConnId = "newConn21"
      "Redirecting" - {
        "Enterprise sending invitation" - {
          ce.enterprise.createNewKey_MFV_0_6(newConnId)
          ce.enterprise.sendInvitation_MFV_0_6(newConnId, includePublicDID = true)
          ce.enterprise.sendGetMsgsByConns("after sending new invitation", 3)
        }
        "Consumer redirecting invitation" - {
          ce.user.createNewKey_MFV_0_6(newConnId)
          ce.user.redirectInvitation_0_6(ce.scenario.connIds.last, newConnId)
        }
        "Enterprise receives conn req redirected" - {
          ce.enterprise.checkExpectedMsgFromEdgeEndpoint("send redirect resp", 3)
          ce.enterprise.sendGetMsgAfterItIsAccepted(newConnId, MSG_TYPE_CONN_REQ_REDIRECTED, MSG_STATUS_REDIRECTED.statusCode, checkRedirect)
        }
      }
    }
  }

  def checkRedirect(respMsg: String): Unit = {
    val redirectJsonObject = new JSONObject(respMsg).getJSONObject("redirectDetail")
    val expectedAttributes = Set("theirDID","theirVerKey","verKey","DID")
    expectedAttributes.foreach { attr =>
      val attrValue = Option(redirectJsonObject.getString(attr))
      attrValue.isDefined shouldBe true
      attrValue.exists(_.nonEmpty) shouldBe true
    }
  }

  def appNameCAS: String
  def appNameEAS: String

  val cas = testEnv.instance_!(appNameCAS)
  val eas = testEnv.instance_!(appNameEAS)
  val consumerAgencyEndpoint = cas.endpoint
  val enterpriseAgencyEndpoint = eas.endpoint

  val requiredAppInstances: List[AppInstance] = List(cas.appInstance, eas.appInstance)
  val agencyScenario = Scenario("Agency setup scenario", requiredAppInstances, suiteTempDir, projectDir)

  val agencyAdminEnv: AgencyAdminEnvironment = AgencyAdminEnvironment(
    agencyScenario,
    casVerityInstance = testEnv.instance_!(appNameCAS),
    easVerityInstance = testEnv.instance_!(appNameEAS),
    executionContextProvider
  )
  def executionContextProvider: ExecutionContextProvider

  //agency environment detail
  setupAgency(agencyAdminEnv)
}

class EdgeHttpListenerForPackedMsg(val appConfig: AppConfig, val listeningEndpoint: UrlParam, executionContext: ExecutionContext) extends PackedMsgHttpListener {
  override def futureExecutionContext: ExecutionContext = executionContext
}
class EdgeHttpListenerForPushNotifMsg(val appConfig: AppConfig, val listeningEndpoint: UrlParam, ec: ExecutionContext) extends PushNotifMsgHttpListener {
  override def futureExecutionContext: ExecutionContext = ec
}

class EnterpriseAgencyAdminExt (scenario: Scenario, verityInstance: VerityInstance, ecp: ExecutionContextProvider)
  extends ApplicationAdminExt(scenario, verityInstance, ecp)
    with AdminClient

class ConsumerAgencyAdminExt (scenario: Scenario, verityInstance: VerityInstance, ecp: ExecutionContextProvider)
  extends ApplicationAdminExt(scenario, verityInstance, ecp)
    with AdminClient


case class AgencyAdminEnvironment (scenario: Scenario,
                                   casVerityInstance: VerityInstance,
                                   easVerityInstance: VerityInstance,
                                   ecp: ExecutionContextProvider) {
  val consumerAgencyAdmin = new ConsumerAgencyAdminExt(scenario, casVerityInstance, ecp)
  val enterpriseAgencyAdmin = new EnterpriseAgencyAdminExt(scenario, easVerityInstance, ecp)
  lazy val casAgencyDidPar = consumerAgencyAdmin.agencyIdentity.didPair
  lazy val easAgencyDidPar = enterpriseAgencyAdmin.agencyIdentity.didPair
}