package com.evernym.verity.integration.v1tov2migration

import akka.http.scaladsl.model.HttpMethods
import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.evernym.verity.actor.agent.user.UpdateTheirRouting
import com.evernym.verity.actor.persistence.recovery.base.AgentIdentifiers._
import com.evernym.verity.actor._
import com.evernym.verity.actor.persistence.recovery.base.PersistenceIdParam
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.constants.ActorNameConstants.{ACTOR_TYPE_USER_AGENT_ACTOR, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR, USER_AGENT_PAIRWISE_REGION_ACTOR_NAME}
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.integration.base.{CAS, VAS, VerityProviderBaseSpec}
import com.evernym.verity.testkit.util.HttpUtil
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.ExecutionContextProvider
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.ExecutionContext


class RoutingMigrationSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val holderCAS = VerityEnvBuilder.default().withConfig(TEST_KIT_CONFIG).build(CAS)
  lazy val issuerVAS = VerityEnvBuilder.default().withConfig(REST_API_CONFIG).build(VAS)

  lazy val verity1HolderSDK = setupIssuerRestSdk(holderCAS, futureExecutionContext)
  lazy val verity2IssuerRestSDK = setupIssuerRestSdk(issuerVAS, futureExecutionContext)

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupVerity1UserAgent()
    setupVerity1UserPairwiseConn()
    setupVerity2EntAgent()
  }

  "Verity1ToVerity2Migration Internal API" - {
    "when asked to migrate CAS routing" - {
      "should be successful" in {
        //start routing migration on CAS
        val utr = UpdateTheirRouting(
          theirPairwiseRelDIDPair.did,
          theirPairwiseRelDIDPair.verKey,
          verity2IssuerRestSDK.agencyDID,
          verity2IssuerRestSDK.agencyVerKey)
        val jsonReq = DefaultMsgCodec.toJson(utr)
        val apiUrl = verity1HolderSDK.buildFullUrl(s"agency/internal/maintenance/v1tov2migration/CAS/connection/${myPairwiseRelDIDPair.did}/routing")

        HttpUtil.sendJsonReqToUrl(jsonReq, apiUrl, method = HttpMethods.PUT)(futureExecutionContext)
        totalPairwiseEvents().count(_.isInstanceOf[TheirRoutingUpdated]) shouldBe 1
        HttpUtil.sendJsonReqToUrl(jsonReq, apiUrl, method = HttpMethods.PUT)(futureExecutionContext)
        totalPairwiseEvents().count(_.isInstanceOf[TheirRoutingUpdated]) shouldBe 1
      }
    }
  }


  def setupVerity2EntAgent(): Unit = {
    verity2IssuerRestSDK.fetchAgencyKey()
    verity2IssuerRestSDK.provisionVerityEdgeAgent()
    verity2IssuerRestSDK.registerWebhookWithoutOAuth()
  }

  def setupVerity1UserAgent(): Unit = {
    val basicUserAgentEvents = scala.collection.immutable.Seq(
      OwnerDIDSet(mySelfRelDIDPair.did, mySelfRelDIDPair.verKey),
      AgentKeyCreated(mySelfRelAgentDIDPair.did, mySelfRelAgentDIDPair.verKey)
    )
    holderCAS.persStoreTestKit.storeAgentRoute(mySelfRelAgentDIDPair.did, ACTOR_TYPE_USER_AGENT_ACTOR, mySelfRelAgentPersistenceId.entityId)
    holderCAS.persStoreTestKit.addEventsToPersistentStorage(mySelfRelAgentPersistenceId, basicUserAgentEvents)
  }

  def setupVerity1UserPairwiseConn(): Unit = {
    val basicUserAgentPairwiseEvents = scala.collection.immutable.Seq(
      OwnerSetForAgent(mySelfRelDIDPair.did, mySelfRelAgentDIDPair.did),
      AgentDetailSet(myPairwiseRelDIDPair.did, myPairwiseRelAgentDIDPair.did),
      AgentKeyDlgProofSet(myPairwiseRelAgentDIDPair.did, myPairwiseRelAgentDIDPair.verKey,"dummy-signature"),
      MsgCreated("001","connReq",myPairwiseRelDIDPair.did,"MS-101",1548446192302L,1548446192302L,"",None),
      MsgCreated("002","connReqAnswer",theirPairwiseRelDIDPair.did,"MS-104",1548446192302L,1548446192302L,"",None),
      MsgAnswered("001","MS-104","002",1548446192302L),
      TheirAgentDetailSet(theirPairwiseRelDIDPair.did, theirPairwiseRelAgentDIDPair.did),
      TheirAgentKeyDlgProofSet(theirPairwiseRelAgentDIDPair.did, theirPairwiseRelAgentDIDPair.verKey,"dummy-signature"),
      TheirAgencyIdentitySet(theirAgencyAgentDIDPair.did, theirAgencyAgentDIDPair.verKey,"0.0.0.1:9000/agency/msg")
    )
    holderCAS.persStoreTestKit.storeAgentRoute(myPairwiseRelDIDPair.did, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR, myPairwiseRelAgentEntityId)
    holderCAS.persStoreTestKit.addEventsToPersistentStorage(myPairwiseRelAgentPersistenceId, basicUserAgentPairwiseEvents)
    holderCAS.persStoreTestKit.createWallet(mySelfRelAgentEntityId)
    holderCAS.persStoreTestKit.createNewKey(mySelfRelAgentEntityId, Option(myPairwiseRelAgentKeySeed))
    holderCAS.persStoreTestKit.storeTheirKey(mySelfRelAgentEntityId, myPairwiseRelDIDPair)
    holderCAS.persStoreTestKit.storeTheirKey(mySelfRelAgentEntityId, theirPairwiseRelDIDPair)
    holderCAS.persStoreTestKit.storeTheirKey(mySelfRelAgentEntityId, theirPairwiseRelAgentDIDPair)
    holderCAS.persStoreTestKit.storeTheirKey(mySelfRelAgentEntityId, theirAgencyAgentDIDPair)
    holderCAS.persStoreTestKit.closeWallet(mySelfRelAgentEntityId)
  }

  def totalPairwiseEvents(): Seq[Any] = {
    holderCAS.persStoreTestKit.getEvents(PersistenceIdParam(USER_AGENT_PAIRWISE_REGION_ACTOR_NAME, myPairwiseRelAgentEntityId))
  }
  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
  override def executionContextProvider: ExecutionContextProvider = ecp

  val REST_API_CONFIG: Config =
    ConfigFactory.parseString(
      """
         verity.rest-api.enabled = true
        """.stripMargin
    )

  private val TEST_KIT_CONFIG =
    ConfigFactory.empty
      .withFallback(EventSourcedBehaviorTestKit.config)
      .withFallback(PersistenceTestKitSnapshotPlugin.config)
}

