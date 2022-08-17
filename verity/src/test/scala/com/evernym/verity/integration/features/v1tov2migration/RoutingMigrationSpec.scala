package com.evernym.verity.integration.features.v1tov2migration

import akka.http.scaladsl.model.HttpMethods
import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.evernym.verity.actor.agent.user.UpdateTheirRouting
import com.evernym.verity.actor.persistence.recovery.base.AgentIdentifiers._
import com.evernym.verity.actor._
import com.evernym.verity.actor.persistence.recovery.base.PersistenceIdParam
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.v1tov2migration.PairwiseUpgradeInfoRespMsg_MFV_1_0
import com.evernym.verity.constants.ActorNameConstants.{ACTOR_TYPE_USER_AGENT_ACTOR, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR, USER_AGENT_PAIRWISE_REGION_ACTOR_NAME}
import com.evernym.verity.constants.Constants.NO
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.integration.base.{CAS, EAS, VAS, VerityProviderBaseSpec}
import com.evernym.verity.testkit.util.HttpUtil
import com.evernym.verity.util2.Status
import com.typesafe.config.{Config, ConfigFactory}



class RoutingMigrationSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val holderCAS = VerityEnvBuilder().withConfig(TEST_KIT_CONFIG).build(CAS)
  lazy val issuerEAS = VerityEnvBuilder().withConfig(TEST_KIT_CONFIG).build(EAS)
  lazy val issuerVAS = VerityEnvBuilder().withConfig(REST_API_CONFIG).build(VAS)

  lazy val verity1HolderSDK = setupHolderSdk(holderCAS, futureExecutionContext)
  lazy val verity1IssuerSDK = setupIssuerSdk(issuerEAS, futureExecutionContext)
  lazy val verity2IssuerRestSDK = setupIssuerRestSdk(issuerVAS, futureExecutionContext)

  override def beforeAll(): Unit = {
    super.beforeAll()
    verity1IssuerSDK.fetchAgencyKey()
    setupVerity1UserAgent()
    setupVerity1UserPairwiseConn()
    setupVerity2EntAgent()
  }

  "Verity1ToVerity2Migration Internal API" - {
    "when asked UPGRADE routing changes on CAS" - {
      "should be successful" in {
        //start routing migration on CAS to point it to VAS
        val utr = UpdateTheirRouting(
          theirPairwiseRelDIDPair.did,
          theirPairwiseRelDIDPair.verKey,
          verity2IssuerRestSDK.agencyDID,
          verity2IssuerRestSDK.agencyVerKey)
        val jsonReq = DefaultMsgCodec.toJson(utr)
        val apiUrl = verity1HolderSDK.buildFullUrl(s"agency/internal/maintenance/v1tov2migration/CAS/connection/${myPairwiseRelDIDPair.did}/routing")
        HttpUtil.sendJsonReqToUrl(jsonReq, apiUrl, method = HttpMethods.PUT)(futureExecutionContext)
        totalPairwiseEvents().count(_.isInstanceOf[TheirRoutingUpdated]) shouldBe 1
        val resp = verity1HolderSDK.downloadMsg[PairwiseUpgradeInfoRespMsg_MFV_1_0](
          "UPGRADE_INFO",
          Option(NO),
          Option(List(Status.MSG_STATUS_RECEIVED.statusCode)),
          Option("connId1")
        )
        val upgradeInfo = resp.msg
        upgradeInfo.direction shouldBe "v1tov2"
        upgradeInfo.theirAgencyEndpoint.startsWith("http") shouldBe true

        HttpUtil.sendJsonReqToUrl(jsonReq, apiUrl, method = HttpMethods.PUT)(futureExecutionContext)
        totalPairwiseEvents().count(_.isInstanceOf[TheirRoutingUpdated]) shouldBe 1
      }
    }

    "when asked to UNDO routing changes on CAS" - {
      "should be successful" in {
        //start routing migration UNDO on CAS to point it to EAS
        val utr = UpdateTheirRouting(
          theirPairwiseRelAgentDIDPair.did,
          theirPairwiseRelAgentDIDPair.verKey,
          verity1IssuerSDK.agencyPublicDid.DID,
          verity1IssuerSDK.agencyPublicDid.verKey)
        val jsonReq = DefaultMsgCodec.toJson(utr)
        val apiUrl = verity1HolderSDK.buildFullUrl(s"agency/internal/maintenance/v1tov2migration/CAS/connection/${myPairwiseRelDIDPair.did}/routing")
        HttpUtil.sendJsonReqToUrl(jsonReq, apiUrl, method = HttpMethods.PUT)(futureExecutionContext)
        totalPairwiseEvents().count(_.isInstanceOf[TheirRoutingUpdated]) shouldBe 2

        val resp = verity1HolderSDK.downloadMsg[PairwiseUpgradeInfoRespMsg_MFV_1_0](
          "UPGRADE_INFO",
          Option(NO),
          Option(List(Status.MSG_STATUS_RECEIVED.statusCode)),
          Option("connId1")
        )
        val upgradeInfo = resp.msg
        upgradeInfo.direction shouldBe "v2tov1"
        upgradeInfo.theirAgencyEndpoint.startsWith("http") shouldBe true

        HttpUtil.sendJsonReqToUrl(jsonReq, apiUrl, method = HttpMethods.PUT)(futureExecutionContext)
        totalPairwiseEvents().count(_.isInstanceOf[TheirRoutingUpdated]) shouldBe 2
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
      OwnerDIDSet(verity1HolderSDK.localAgentDidPair.did, verity1HolderSDK.localAgentDidPair.verKey),
      AgentKeyCreated(mySelfRelAgentDIDPair.did, mySelfRelAgentDIDPair.verKey),
      AgentDetailSet(myPairwiseRelDIDPair.did, myPairwiseRelAgentDIDPair.did,
        myPairwiseRelDIDPair.verKey, myPairwiseRelAgentDIDPair.verKey
      )
    )
    holderCAS.persStoreTestKit.storeAgentRoute(mySelfRelAgentDIDPair.did, ACTOR_TYPE_USER_AGENT_ACTOR, mySelfRelAgentPersistenceId.entityId)
    holderCAS.persStoreTestKit.addEventsToPersistentStorage(mySelfRelAgentPersistenceId, basicUserAgentEvents)
    holderCAS.persStoreTestKit.createWallet(mySelfRelAgentEntityId)
    holderCAS.persStoreTestKit.createNewKey(mySelfRelAgentEntityId, Option(mySelfRelAgentDIDKeySeed))
    holderCAS.persStoreTestKit.storeTheirKey(mySelfRelAgentEntityId, verity1HolderSDK.localAgentDidPair)

    verity1HolderSDK.fetchAgencyKey()
    verity1HolderSDK.updateVerityAgentDidPair(mySelfRelAgentDIDPair)
  }

  def setupVerity1UserPairwiseConn(): Unit = {
    val basicUserAgentPairwiseEvents = scala.collection.immutable.Seq(
      OwnerSetForAgent(verity1HolderSDK.localAgentDidPair.did, mySelfRelAgentDIDPair.did),
      AgentDetailSet(myPairwiseRelDIDPair.did, myPairwiseRelAgentDIDPair.did),
      AgentKeyDlgProofSet(myPairwiseRelAgentDIDPair.did, myPairwiseRelAgentDIDPair.verKey,"dummy-signature"),
      MsgCreated("001","connReq",myPairwiseRelDIDPair.did,"MS-101",1548446192302L,1548446192302L,"",None),
      MsgCreated("002","connReqAnswer",theirPairwiseRelDIDPair.did,"MS-104",1548446192302L,1548446192302L,"",None),
      MsgAnswered("001","MS-104","002",1548446192302L),
      TheirAgentDetailSet(theirPairwiseRelDIDPair.did, theirPairwiseRelAgentDIDPair.did),
      TheirAgentKeyDlgProofSet(theirPairwiseRelAgentDIDPair.did, theirPairwiseRelAgentDIDPair.verKey,"dummy-signature"),
      TheirAgencyIdentitySet(verity1IssuerSDK.agencyPublicDid.DID, verity1IssuerSDK.agencyPublicDid.verKey,"0.0.0.1:9000/agency/msg")
    )
    holderCAS.persStoreTestKit.storeAgentRoute(myPairwiseRelDIDPair.did, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR, myPairwiseRelAgentEntityId)
    holderCAS.persStoreTestKit.storeAgentRoute(myPairwiseRelAgentDIDPair.did, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR, myPairwiseRelAgentEntityId)
    holderCAS.persStoreTestKit.addEventsToPersistentStorage(myPairwiseRelAgentPersistenceId, basicUserAgentPairwiseEvents)
    holderCAS.persStoreTestKit.createNewKey(mySelfRelAgentEntityId, Option(myPairwiseRelDIDKeySeed))
    holderCAS.persStoreTestKit.createNewKey(mySelfRelAgentEntityId, Option(myPairwiseRelAgentKeySeed))
    holderCAS.persStoreTestKit.storeTheirKey(mySelfRelAgentEntityId, myPairwiseRelDIDPair)
    holderCAS.persStoreTestKit.storeTheirKey(mySelfRelAgentEntityId, theirPairwiseRelDIDPair)
    holderCAS.persStoreTestKit.storeTheirKey(mySelfRelAgentEntityId, theirPairwiseRelAgentDIDPair)
    holderCAS.persStoreTestKit.storeTheirKey(mySelfRelAgentEntityId, theirAgencyAgentDIDPair)

    verity1HolderSDK.createNewKey(Option(myPairwiseRelDIDKeySeed))
    verity1HolderSDK.updatePairwiseAgentDidPair("connId1", myPairwiseRelDIDPair, myPairwiseRelAgentDIDPair)
  }

  def totalPairwiseEvents(): Seq[Any] = {
    holderCAS.persStoreTestKit.getEvents(PersistenceIdParam(USER_AGENT_PAIRWISE_REGION_ACTOR_NAME, myPairwiseRelAgentEntityId))
  }

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

