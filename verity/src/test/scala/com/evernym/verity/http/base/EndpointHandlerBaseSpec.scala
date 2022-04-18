package com.evernym.verity.http.base

import akka.pattern.ask
import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import com.evernym.verity.actor.AppStateCoordinator
import com.evernym.verity.constants.Constants.URL
import com.evernym.verity.actor.testkit.{AkkaTestBasic, CommonSpecUtil}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.http.base.open._
import com.evernym.verity.http.base.restricted.{AgencySetupSpec, AgentConfigsSpec, AppStatusHealthCheckSpec, MockHealthChecker, RestrictedRestApiSpec}
import com.evernym.verity.http.route_handlers.EndpointHandlerBase
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.protocol.protocols.connecting.common.InviteDetail
import com.evernym.verity.testkit.BasicSpecWithIndyCleanup
import com.evernym.verity.testkit.agentmsg.AgentMsgPackagingContext
import com.evernym.verity.testkit.mock.pushnotif.MockPushNotifListener
import com.evernym.verity.testkit.mock.msgsendingsvc.MockMsgSendingSvcListener
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.testkit.mock.agent.{MockCloudAgent, MockEdgeAgent, MockEnvUtil}
import com.evernym.verity.util.healthcheck.HealthChecker
import org.mockito.MockitoSugar.mock
import org.scalatest.concurrent.Eventually

import scala.concurrent.Await
import scala.reflect.ClassTag
import scala.concurrent.duration._

trait EdgeEndpointBaseSpec
  extends BasicSpecWithIndyCleanup
    with ScalatestRouteTest
    with EndpointHandlerBase
    with Eventually
    with CommonSpecUtil
    with ApiClientSpecCommon
    with AgentReqBuilder
    with MockPushNotifListener
    with MockMsgSendingSvcListener
    with AriesInvitationDecodingSpec
    with AppStatusHealthCheckSpec {

  lazy val (mockEntEdgeEnv, mockUserEdgeEnv) = {
    val edge1 = MockEnvUtil.buildNewEnv("edge1", appConfig, "localhost:9001/agency/msg", futureExecutionContext)
    val edge2 = MockEnvUtil.buildNewEnv("edge2", appConfig, "localhost:9002/agency/msg", futureExecutionContext)
    (edge1.withOthersMockEnvSet(edge2), edge2.withOthersMockEnvSet(edge1))
  }

  lazy val mockVerityEnv: MockEnvUtil = MockEnvUtil(system, appConfig)
  mockVerityEnv.addNewEnv(mockEntEdgeEnv)
  mockVerityEnv.addNewEnv(mockUserEdgeEnv)

  def setupAgencyWithRemoteAgentAndAgencyIdentities(agencyMockEnv: MockCloudAgent,
                                                    detail: RemoteAgentAndAgencyIdentity): Unit = {
    agencyMockEnv.setupRemoteAgentAndAgencyIdentity(detail)
  }

  lazy val inviteSenderName: String = edgeAgentName
  lazy val inviteSenderLogoUrl: String = edgeAgentLogoUrl

  val AKKA_HTTP_ROUTE_TEST_TIMEOUT_CONFIG_NAME = "verity.test.http-route-timeout-in-seconds"

  implicit def default(implicit system: ActorSystem): RouteTestTimeout =
    RouteTestTimeout(buildDurationInSeconds(appConfig.getIntReq(AKKA_HTTP_ROUTE_TEST_TIMEOUT_CONFIG_NAME)))

  override def checkIfInternalApiCalledFromAllowedIPAddresses(callerIpAddress: String)(implicit req: HttpRequest): Unit = {
    logger.debug("api request allowed from test case: " + req.uri, ("req_uri", req.uri))
  }

  def getInviteUrl(edgeAgent: MockEdgeAgent): String = {
    edgeAgent.inviteUrl.split(":", 2).last.split("/", 2).last
  }

  def addAgencyEndpointToLedger(agencyDID: DidStr, endpoint: String): Unit = {
    platform.agentActorContext.ledgerSvc.addAttrib(null, agencyDID,
      URL, endpoint)
  }

  def buildInvalidRemoteAgentKeyDlgProof(inviteDetail: InviteDetail): InviteDetail = {
    val iadp = inviteDetail.senderDetail.agentKeyDlgProof.map(_.copy(agentDID = "garbage"))
    val sd = inviteDetail.senderDetail.copy(agentKeyDlgProof = iadp)
    inviteDetail.copy(senderDetail = sd)
  }

  def emptyPackedMsgWrapper: PackedMsg = PackedMsg(Array[Byte]())
  def responseTo[T: ClassTag]: T = DefaultMsgCodec.fromJson(responseAs[String])

  override protected def createActorSystem(): ActorSystem = {
    val port = appConfig.getIntReq("akka.remote.artery.canonical.port")
    val systemName = AkkaTestBasic.systemNameForPort(port)
    ActorSystem(systemName, appConfig.config)
  }

  def epRoutes: Route = endpointRoutes

  def sendToAppStateManager[T](cmd: Any): T = {
    val fut = platform.appStateManager ? cmd
    Await.result(fut, 5.seconds).asInstanceOf[T]
  }

  override val healthChecker: HealthChecker = mock[MockHealthChecker]
  override val appStateCoordinator: AppStateCoordinator = mock[AppStateCoordinator]
}

trait EndpointHandlerBaseSpec
  extends EdgeEndpointBaseSpec
    with AgencySetupSpec
    with RestrictedRestApiSpec
    with AgentProvisioningSpec
    with AgentConfigsSpec
    with UpdateComMethodSpec
    with ProvisionRelationshipSpec
    with GetMsgsSpec
    with WalletBackupAndRecoverySpec {

  "Endpoint spec" - {

    testSetAppStateAsListening()

    testCheckAppStateIsListening()

    testAgencySetup()

    testAgentProvisioning(mockVerityEnv.getEnv("edge1"))

    testUpdateComMethod(mockVerityEnv.getEnv("edge1"))

    testGetMsgsByConnections(mockVerityEnv.getEnv("edge1"), 0)

    //this should contain agent type (consumer/enterprise/verity etc) specific tests
    testEdgeAgent()

    testOpenRestApis()

    testRestrictedRestApis()
  }

  def testEdgeAgent(): Unit

  implicit def msgPackagingContext: AgentMsgPackagingContext

}

case class RemoteAgentAndAgencyIdentity(agentDID: DidStr, agentVerKey: VerKeyStr,
                                        agencyDID: DidStr, agencyVerKey: VerKeyStr)


trait ApiClientSpecCommon {
  val connIda1 = "connIda1"
  val connIda2 = "connIda2"
  val connIda3 = "connIda3"

  val connIdb1 = "connIdb1"
  val connIdb2 = "connIdb2"
  val connIdb3 = "connIdb3"

  var inviteUrl: String = _
}