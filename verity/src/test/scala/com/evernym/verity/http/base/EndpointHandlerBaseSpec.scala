package com.evernym.verity.http.base

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import com.evernym.verity.constants.Constants.URL
import com.evernym.verity.actor.testkit.{AkkaTestBasic, CommonSpecUtil}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.config.AppConfig
import com.evernym.verity.http.base.open._
import com.evernym.verity.http.base.restricted.{AgencySetupSpec, AgentConfigsSpec, RestrictedRestApiSpec}
import com.evernym.verity.http.route_handlers.EndpointHandlerBase
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.protocol.protocols.connecting.common.InviteDetail
import com.evernym.verity.testkit.BasicSpecWithIndyCleanup
import com.evernym.verity.testkit.agentmsg.AgentMsgPackagingContext
import com.evernym.verity.testkit.mock.agency_admin.MockAgencyAdmin
import com.evernym.verity.testkit.mock.cloud_agent.{MockCloudAgentBase, MockConsumerCloudAgent, MockEntCloudAgent}
import com.evernym.verity.testkit.mock.edge_agent.{MockConsumerEdgeAgent, MockEntEdgeAgent}
import com.evernym.verity.testkit.mock.pushnotif.MockPushNotifListener
import com.evernym.verity.testkit.mock.remotemsgsendingsvc.MockRemoteMsgSendingSvcListener
import com.evernym.verity.testkit.util.TestUtil
import com.evernym.verity.util._
import com.evernym.verity.UrlDetail
import com.evernym.verity.actor.wallet.PackedMsg
import org.scalatest.concurrent.Eventually

import scala.reflect.ClassTag

trait EndpointHandlerBaseSpec
  extends ScalatestRouteTest
    with EndpointHandlerBase
    with BasicSpecWithIndyCleanup
    with Eventually
    with CommonSpecUtil
    with ApiClientSpecCommon
    with AgentReqBuilder
    with MockPushNotifListener
    with AgencySetupSpec
    with RestrictedRestApiSpec
    with AgentProvisioningSpec
    with AgentConfigsSpec
    with UpdateComMethodSpec
    with ProvisionRelationshipSpec
    with GetMsgsSpec
    with TokenTransferSpec
    with RestApiSpec
    with WalletBackupAndRecoverySpec
    with MockRemoteMsgSendingSvcListener
    with AriesInvitationDecodingSpec {

  "Endpoint spec" - {

    testSetAppStateAsListening()

    testCheckAppStateIsListening()

    testAgencySetup()

    testAgentProvisioning()

    testUpdateComMethod()

    testGetMsgsByConnections(0)

    //this should contain agent type (consumer/enterprise/verity etc) specific tests
    testEdgeAgent()

    testTokenTransferSpec()

    testOpenRestApis()

    testRestrictedRestApis()
  }

  lazy val mockConsumerAgencyAdmin = new MockAgencyAdmin(system, UrlDetail("localhost:9001/agency/msg"), appConfig)
  lazy val mockConsumerCloudAgent: MockConsumerCloudAgent = buildMockConsumerCloudAgent(mockConsumerAgencyAdmin)
  lazy val mockConsumerEdgeAgent1: MockConsumerEdgeAgent = buildMockConsumerEdgeAgent(mockConsumerAgencyAdmin)
  lazy val mockConsumerEdgeAgent2: MockConsumerEdgeAgent = buildMockConsumerEdgeAgent(mockConsumerAgencyAdmin)

  lazy val mockEntAgencyAdmin = new MockAgencyAdmin(system, UrlDetail("localhost:9002/agency/msg"), appConfig)
  lazy val mockEntCloudAgent: MockEntCloudAgent = buildMockEntCloudAgent(mockEntAgencyAdmin)
  lazy val mockEntEdgeAgent1: MockEntEdgeAgent = buildMockEnterpriseEdgeAgent(mockEntAgencyAdmin)
  lazy val mockEntEdgeAgent2: MockEntEdgeAgent = buildMockEnterpriseEdgeAgent(mockEntAgencyAdmin)


  def testEdgeAgent(): Unit

  implicit def msgPackagingContext: AgentMsgPackagingContext

  override protected def createActorSystem(): ActorSystem = AkkaTestBasic.system()

  def epRoutes: Route = endpointRoutes

  def appConfig: AppConfig

  def responseTo[T: ClassTag]: T = DefaultMsgCodec.fromJson(responseAs[String])

  lazy val inviteSenderName: String = edgeAgentName
  lazy val inviteSenderLogoUrl: String = edgeAgentLogoUrl
  lazy val util: UtilBase = TestUtil

  // Disable rest api by default, we will override it in the test, when needed,
  // but this way we may check if it responds correctly when disabled.
  lazy override val restApiEnabled: Boolean = false
  var overrideRestEnable: Boolean = true

  override def checkIfRestApiEnabled(): Unit = {
    if (!overrideRestEnable)
      super.checkIfRestApiEnabled()
  }

  val AKKA_HTTP_ROUTE_TEST_TIMEOUT_CONFIG_NAME = "akka.test.http-route-timeout-in-seconds"

  implicit def default(implicit system: ActorSystem): RouteTestTimeout =
    RouteTestTimeout(buildDurationInSeconds(appConfig.getConfigIntReq(AKKA_HTTP_ROUTE_TEST_TIMEOUT_CONFIG_NAME)))

  override def checkIfInternalApiCalledFromAllowedIPAddresses(callerIpAddress: String)(implicit req: HttpRequest): Unit = {
    logger.debug("api request allowed from test case: " + req.uri, ("req_uri", req.uri))
  }

  def buildMockConsumerEdgeAgent(mockAgencyAdmin: MockAgencyAdmin): MockConsumerEdgeAgent = {
    val mcea = new MockConsumerEdgeAgent(UrlDetail("localhost:9001/agency/msg"), appConfig)
    mcea.agencyPublicDid = Option(mockAgencyAdmin.myDIDDetail.prepareAgencyIdentity)
    mcea
  }

  def buildMockConsumerCloudAgent(mockAgencyAdmin: MockAgencyAdmin): MockConsumerCloudAgent = {
    val mcea = new MockConsumerCloudAgent(system, appConfig)
    mcea.agencyPublicDid = Option(mockAgencyAdmin.myDIDDetail.prepareAgencyIdentity)
    mcea
  }

  def buildMockEntCloudAgent(mockAgencyAdmin: MockAgencyAdmin): MockEntCloudAgent = {
    val mcea = new MockEntCloudAgent(system, appConfig)
    mcea.agencyPublicDid = Option(mockAgencyAdmin.myDIDDetail.prepareAgencyIdentity)
    mcea
  }

  def buildMockEnterpriseEdgeAgent(mockAgencyAdmin: MockAgencyAdmin): MockEntEdgeAgent = {
    val meea = new MockEntEdgeAgent(UrlDetail("localhost:9002/agency/msg"), appConfig)
    meea.agencyPublicDid = Option(mockAgencyAdmin.myDIDDetail.prepareAgencyIdentity)
    meea
  }

  def getInviteUrl(edgeAgent: MockEntEdgeAgent): String = {
    edgeAgent.inviteUrl.split(":", 2).last.split("/", 2).last
  }

  def emptyPackedMsgWrapper: PackedMsg = PackedMsg(Array[Byte]())

  def addAgencyEndpointToLedger(agencyDID: DID, endpoint: String): Unit = {
    platform.agentActorContext.ledgerSvc.addAttrib(null, agencyDID,
      URL, endpoint)
  }

  def buildInvalidRemoteAgentKeyDlgProof(inviteDetail: InviteDetail): InviteDetail = {
    val iadp = inviteDetail.senderDetail.agentKeyDlgProof.map(_.copy(agentDID = "garbage"))
    val sd = inviteDetail.senderDetail.copy(agentKeyDlgProof = iadp)
    inviteDetail.copy(senderDetail = sd)
  }

  def setupAgencyWithRemoteAgentAndAgencyIdentities(agencyMockEnv: MockCloudAgentBase, detail: RemoteAgentAndAgencyIdentity): Unit = {
    agencyMockEnv.setupRemoteAgentAndAgencyIdentity(detail)
  }

}

trait ApiClientSpecCommon {
  val connIda1 = "connIda1"
  val connIda2 = "connIda2"
  val connIda3 = "connIda3"

  val connIdb1 = "connIdb1"
  val connIdb2 = "connIdb2"
  val connIdb3 = "connIdb3"

  var inviteUrl: String = _
}

case class RemoteAgentAndAgencyIdentity(agentDID: DID, agentVerKey: VerKey,
                                        agencyDID: DID, agencyVerKey: VerKey)
