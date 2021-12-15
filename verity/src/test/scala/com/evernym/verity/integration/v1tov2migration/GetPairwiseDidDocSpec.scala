package com.evernym.verity.integration.v1tov2migration

import akka.actor.ActorSystem
import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.evernym.verity.actor.agent.user.{GetPairwiseConnDetailResp, PairwiseDidDoc}
import com.evernym.verity.actor.persistence.recovery.base.{AgentIdentifiers, BasePersistentStore}
import com.evernym.verity.actor.{AgentDetailSet, AgentKeyCreated, AgentKeyDlgProofSet, MsgAnswered, MsgCreated, OwnerDIDSet, OwnerSetForAgent, TheirAgencyIdentitySet, TheirAgentDetailSet, TheirAgentKeyDlgProofSet}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.constants.ActorNameConstants.{ACTOR_TYPE_USER_AGENT_ACTOR, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR}
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.integration.base.verity_provider.node.local.VerityLocalNode
import com.evernym.verity.integration.base.{EAS, VerityProviderBaseSpec}
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.ExecutionContextProvider
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContext


class GetPairwiseDidDocSpec
  extends VerityProviderBaseSpec
    with SdkProvider
    with BasePersistentStore
    with AgentIdentifiers {

  override def beforeAll(): Unit = {
    //set up data
    super.beforeAll()
    setupUserAgent()
    setupUserAgentPairwise()
  }

  lazy val issuerEAS = VerityEnvBuilder.default().withConfig(TEST_KIT_CONFIG).build(EAS)
  lazy val issuerRestSDK = setupIssuerRestSdk(issuerEAS, futureExecutionContext)


  "Verity1ToVerity2Migration Internal API" - {
    "when asked for pairwise did doc" - {
      "should respond with correct data" in {
        val basePath = s"agency/internal/maintenance/v1tov2migration/connection/${myPairwiseRelDIDPair.did}/diddoc"
        val apiResp = issuerRestSDK.sendGET(s"$basePath")
        val respString = issuerRestSDK.parseHttpResponseAsString(apiResp)
        val resp = DefaultMsgCodec.fromJson[GetPairwiseConnDetailResp](respString)

        resp.connAnswerStatusCode shouldBe "MS-104"
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
      }
    }
  }

  def setupUserAgent(): Unit = {
    val basicUserAgentEvents = scala.collection.immutable.Seq(
      OwnerDIDSet(mySelfRelDIDPair.did, mySelfRelDIDPair.verKey),
      AgentKeyCreated(mySelfRelAgentDIDPair.did, mySelfRelAgentDIDPair.verKey)
    )
    storeAgentRoute(mySelfRelAgentDIDPair.did, ACTOR_TYPE_USER_AGENT_ACTOR, mySelfRelAgentPersistenceId.entityId)
    addEventsToPersistentStorage(mySelfRelAgentPersistenceId, basicUserAgentEvents)
  }

  def setupUserAgentPairwise(): Unit = {
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
    storeAgentRoute(myPairwiseRelDIDPair.did, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR, myPairwiseRelAgentEntityId)
    addEventsToPersistentStorage(myPairwiseRelAgentPersistenceId, basicUserAgentPairwiseEvents)
    createWallet(mySelfRelAgentEntityId)
    createNewKey(mySelfRelAgentEntityId, Option(myPairwiseRelAgentKeySeed))
    storeTheirKey(mySelfRelAgentEntityId, myPairwiseRelDIDPair)
    storeTheirKey(mySelfRelAgentEntityId, theirPairwiseRelDIDPair)
    storeTheirKey(mySelfRelAgentEntityId, theirPairwiseRelAgentDIDPair)
    storeTheirKey(mySelfRelAgentEntityId, theirAgencyAgentDIDPair)
    closeWallet(mySelfRelAgentEntityId)
  }

  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
  override def executionContextProvider: ExecutionContextProvider = ecp

  override implicit val system: ActorSystem = issuerEAS.nodes.head.asInstanceOf[VerityLocalNode].platform.actorSystem

  lazy val TEST_KIT_CONFIG =
    ConfigFactory.empty
      .withFallback(EventSourcedBehaviorTestKit.config)
      .withFallback(PersistenceTestKitSnapshotPlugin.config)

}
