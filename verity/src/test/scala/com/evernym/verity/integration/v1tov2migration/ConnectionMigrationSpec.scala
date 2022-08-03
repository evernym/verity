package com.evernym.verity.integration.v1tov2migration

import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.evernym.verity.actor.{AgencyPublicDid, AgentDetailSet, AgentKeyCreated, AgentKeyDlgProofSet, MsgAnswered, MsgCreated, OwnerDIDSet, OwnerSetForAgent, TheirAgencyIdentitySet, TheirAgentDetailSet, TheirAgentKeyDlgProofSet}
import com.evernym.verity.actor.agent.user.{GetPairwiseConnDetailResp, PairwiseDidDoc}
import com.evernym.verity.actor.maintenance.v1tov2migration.{Agent, Connection, MyPairwiseDidDoc, SetupMigratedConnection, TheirPairwiseDidDoc}
import com.evernym.verity.actor.persistence.recovery.base.PersistenceIdParam
import com.evernym.verity.actor.persistence.recovery.base.AgentIdentifiers._
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.config.ConfigConstants
import com.evernym.verity.constants.ActorNameConstants.{ACTOR_TYPE_USER_AGENT_ACTOR, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR, ROUTE_REGION_ACTOR_NAME, USER_AGENT_PAIRWISE_REGION_ACTOR_NAME}
import com.evernym.verity.integration.base.{CAS, EAS, VAS, VerityProviderBaseSpec}
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.protocol.engine.registry.PinstIdResolution.V0_2
import com.evernym.verity.protocol.protocols.relationship.v_1_0.RelationshipDef
import com.evernym.verity.testkit.util.HttpUtil
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.ExecutionContextProvider
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import java.util.UUID
import scala.concurrent.ExecutionContext


class ConnectionMigrationSpec
  extends VerityProviderBaseSpec
    with SdkProvider
    with Eventually {

  lazy val holderCAS = VerityEnvBuilder.default().build(CAS)
  lazy val issuerEAS = VerityEnvBuilder.default().withConfig(TEST_KIT_CONFIG).build(EAS)
  lazy val issuerVAS = VerityEnvBuilder.default().withConfig(REST_API_CONFIG.withFallback(TEST_KIT_CONFIG)).build(VAS)

  lazy val verity1HolderSDK = setupHolderSdk(holderCAS, futureExecutionContext)
  lazy val verity1IssuerRestSDK = setupIssuerRestSdk(issuerEAS, futureExecutionContext)
  lazy val verity2IssuerRestSDK = setupIssuerRestSdk(issuerVAS, futureExecutionContext)

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupVerity1EntAgent()
    setupVerity1EntPairwiseConn()
    setupVerity2EntAgent()
  }

  "Verity1ToVerity2Migration Internal API" - {
    "when asked to migrate connection" - {
      "should be successful" in {
        // collect required data to be used in connection migration
        val verity1ConnDetail = {
          val urlSuffix = s"agency/internal/maintenance/v1tov2migration/connection/${myPairwiseRelDIDPair.did}/diddoc"
          val apiResp = HttpUtil.sendGET(verity1IssuerRestSDK.buildFullUrl(s"$urlSuffix"))(futureExecutionContext)
          val respString = HttpUtil.parseHttpResponseAsString(apiResp)(futureExecutionContext)
          val resp = DefaultMsgCodec.fromJson[GetPairwiseConnDetailResp](respString)
          resp.myDidDoc shouldBe
            PairwiseDidDoc(
              myPairwiseRelDIDPair.did,
              myPairwiseRelDIDPair.verKey,
              myPairwiseRelAgentDIDPair.did,
              myPairwiseRelAgentDIDPair.verKey
            )
          resp.theirDidDoc shouldBe
            PairwiseDidDoc(
              theirPairwiseRelDIDPair.did,
              theirPairwiseRelDIDPair.verKey,
              theirPairwiseRelAgentDIDPair.did,
              theirPairwiseRelAgentDIDPair.verKey
            )
          resp
        }

        val casAgencyDetail = {
          val apiResp = HttpUtil.sendGET(verity1HolderSDK.buildFullUrl("agency"))(futureExecutionContext)
          val respString = HttpUtil.parseHttpResponseAsString(apiResp)(futureExecutionContext)
          DefaultMsgCodec.fromJson[AgencyPublicDid](respString)
        }

        //start connection migration
        val smc = SetupMigratedConnection(
          Agent(verity2IssuerRestSDK.verityAgentDidPair.did, verity2IssuerRestSDK.verityAgentDidPair.verKey),
          Connection(
            "MS-104",
            MyPairwiseDidDoc(
              verity1ConnDetail.myDidDoc.pairwiseDID,
              verity1ConnDetail.myDidDoc.pairwiseDIDVerKey
            ),
            TheirPairwiseDidDoc(
              verity1ConnDetail.theirDidDoc.pairwiseDID, verity1ConnDetail.theirDidDoc.pairwiseDIDVerKey,
              verity1ConnDetail.theirDidDoc.pairwiseAgentDID, verity1ConnDetail.theirDidDoc.pairwiseAgentVerKey,
              holderCAS.endpointProvider.availableNodeUrls.head + "/agency/msg",
              casAgencyDetail.DID, casAgencyDetail.verKey
            )
          )
        )
        val jsonReq = DefaultMsgCodec.toJson(smc)
        val urlSuffix = s"agency/internal/maintenance/v1tov2migration/VAS/connection/${myPairwiseRelDIDPair.did}/diddoc"
        HttpUtil.sendJsonReqToUrl(jsonReq, verity2IssuerRestSDK.buildFullUrl(s"$urlSuffix"))(futureExecutionContext)
        checkPersistedEvents(smc)

        HttpUtil.sendJsonReqToUrl(jsonReq, verity2IssuerRestSDK.buildFullUrl(s"$urlSuffix"))(futureExecutionContext)
        checkPersistedEvents(smc)
      }
    }
  }

  def checkPersistedEvents(smc: SetupMigratedConnection): Unit = {
    eventually(timeout(Span(5, Seconds)), interval(Span(200, Millis))) {
      val pairwiseAgentActorEntityId = UUID.nameUUIDFromBytes((smc.agent.agentDID + smc.connection.myDidDoc.pairwiseDID).getBytes()).toString
      issuerVAS.persStoreTestKit.getEvents(PersistenceIdParam(USER_AGENT_PAIRWISE_REGION_ACTOR_NAME, pairwiseAgentActorEntityId)).size shouldBe 3
      issuerVAS.persStoreTestKit.getEvents(
        PersistenceIdParam(ROUTE_REGION_ACTOR_NAME, smc.connection.myDidDoc.pairwiseDID),
        Option(issuerVAS.headVerityLocalNode.platform.appConfig.getStringReq(ConfigConstants.SECRET_ROUTING_AGENT))
      ).size shouldBe 1
      val pinstId = V0_2.resolve(
        RelationshipDef,
        smc.agent.agentDID,
        Option(smc.agent.agentDID),
        Option(UUID.nameUUIDFromBytes(smc.connection.myDidDoc.pairwiseDID.getBytes()).toString),
        None,
        None
      )
      issuerVAS.persStoreTestKit.getEvents(PersistenceIdParam("relationship-1.0-protocol", pinstId)).size shouldBe 7
    }
  }

  def setupVerity2EntAgent(): Unit = {
    verity2IssuerRestSDK.fetchAgencyKey()
    verity2IssuerRestSDK.provisionVerityEdgeAgent()
    verity2IssuerRestSDK.registerWebhookWithoutOAuth()
  }

  def setupVerity1EntAgent(): Unit = {
    val basicUserAgentEvents = scala.collection.immutable.Seq(
      OwnerDIDSet(mySelfRelDIDPair.did, mySelfRelDIDPair.verKey),
      AgentKeyCreated(mySelfRelAgentDIDPair.did, mySelfRelAgentDIDPair.verKey)
    )
    issuerEAS.persStoreTestKit.storeAgentRoute(mySelfRelAgentDIDPair.did, ACTOR_TYPE_USER_AGENT_ACTOR, mySelfRelAgentPersistenceId.entityId)
    issuerEAS.persStoreTestKit.addEventsToPersistentStorage(mySelfRelAgentPersistenceId, basicUserAgentEvents)
  }

  def setupVerity1EntPairwiseConn(): Unit = {
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
    issuerEAS.persStoreTestKit.storeAgentRoute(myPairwiseRelDIDPair.did, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR, myPairwiseRelAgentEntityId)
    issuerEAS.persStoreTestKit.addEventsToPersistentStorage(myPairwiseRelAgentPersistenceId, basicUserAgentPairwiseEvents)
    issuerEAS.persStoreTestKit.createWallet(mySelfRelAgentEntityId)
    issuerEAS.persStoreTestKit.createNewKey(mySelfRelAgentEntityId, Option(myPairwiseRelAgentKeySeed))
    issuerEAS.persStoreTestKit.storeTheirKey(mySelfRelAgentEntityId, myPairwiseRelDIDPair)
    issuerEAS.persStoreTestKit.storeTheirKey(mySelfRelAgentEntityId, theirPairwiseRelDIDPair)
    issuerEAS.persStoreTestKit.storeTheirKey(mySelfRelAgentEntityId, theirPairwiseRelAgentDIDPair)
    issuerEAS.persStoreTestKit.storeTheirKey(mySelfRelAgentEntityId, theirAgencyAgentDIDPair)
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

