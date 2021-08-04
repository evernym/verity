package com.evernym.verity.actor.agent.snapshot

import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.{ForIdentifier, KeyCreated}
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_MSG_PACK
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, GetStoredRoute}
import com.evernym.verity.actor.agent.user.{UserAgentPairwiseSpecScaffolding, UserAgentPairwiseState}
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.actor.testkit.actor.OverrideConfig
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.constants.ActorNameConstants.USER_AGENT_PAIRWISE_REGION_ACTOR_NAME
import com.evernym.verity.protocol.engine.Constants.MTV_1_0
import com.evernym.verity.did.DidStr
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.testkit.agentmsg.AgentMsgPackagingContext
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.ExecutionContext

class UserAgentPairwiseSnapshotSpec
  extends BasicSpec
    with UserAgentPairwiseSpecScaffolding
    with PersistentActorSpec
    with SnapshotSpecBase
    with OverrideConfig {

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupAgency()
    createUserAgent()
  }

  override def overrideConfig: Option[Config] = Option(
    ConfigFactory.parseString(
      """verity.persistent-actor.base.UserAgentPairwise.snapshot {
        after-n-events = 1
        keep-n-snapshots = 2
        delete-events-on-snapshots = false
      }""")
      .withFallback(EventSourcedBehaviorTestKit.config)
      .withFallback(PersistenceTestKitSnapshotPlugin.config)
  )

  "UserAgentPairwise actor" - {
    "as its state changes" - {
      "will write snapshot as per configuration" in {

        // check current status (at this time, agency agent setup is done)
        checkPersistentState(0, 0, 0)

        //init agent pairwise request
        initUserAgentPairwise()
        checkPersistentState(2, 2, 0)

        //restart actor
        restartPersistentActor(ua)
        checkPersistentState(2, 2, 0)

        //check metrics for state size
        checkStateSizeMetrics("UserAgentPairwise", 26.0)
      }
    }
  }

  def initUserAgentPairwise(): Unit = {
    import mockEdgeAgent.v_0_5_req._
    import mockEdgeAgent.v_0_5_resp._

    val msg = prepareCreateKeyMsgForAgent(connId1)
    ua ! wrapAsPackedMsgParam(msg)
    val pm = expectMsgType[PackedMsg]
    val keyCreated = handleKeyCreatedResp(pm, Map(CONN_ID -> connId1))
    pairwiseDID = keyCreated.withPairwiseDID
    updateUserAgentPairwiseEntityId()
  }

  def updateUserAgentPairwiseEntityId(): Unit = {
    routeRegion ! ForIdentifier(pairwiseDID, GetStoredRoute)
    val addressDetail = expectMsgType[Option[ActorAddressDetail]]
    addressDetail.isDefined shouldBe true
    userAgentPairwiseEntityId = addressDetail.get.address
  }

  def checkKeyCreatedEvent(keyCreated: KeyCreated, expectedForDID: DidStr): Unit = {
    keyCreated.forDID shouldBe expectedForDID
  }

  override def checkSnapshotState(state: UserAgentPairwiseState,
                                  protoInstancesSize: Int): Unit = {
    val myDIDDetail = mockEdgeAgent.pairwiseConnDetail(connId1).myPairwiseDidPair

    state.agencyDIDPair shouldBe mockAgencyAdmin.agencyPublicDid.map(_.didPair)
    state.agentWalletId shouldBe Option(userAgentEntityId)
    state.thisAgentKeyId.isDefined shouldBe true
    state.thisAgentKeyId.contains(myDIDDetail.did) shouldBe false

    state.relationshipReq.name shouldBe "pairwise"
    state.relationshipReq.myDidDoc.isDefined shouldBe true
    state.relationshipReq.myDidDoc_!.did shouldBe myDIDDetail.did

    //this is found only for pairwise actors and only for those protocols
    // which starts (the first message) from self-relationship actor and then
    // continues (rest messages) with a pairwise actor
    val expectedProtoInstancesSize = if (protoInstancesSize == 0) None else Option(protoInstancesSize)
    state.protoInstances.map(_.instances.size) shouldBe expectedProtoInstancesSize
  }

  override type StateType = UserAgentPairwiseState
  override def regionActorName: String = USER_AGENT_PAIRWISE_REGION_ACTOR_NAME
  override def actorEntityId: String = userAgentPairwiseEntityId

  override implicit val msgPackagingContext: AgentMsgPackagingContext =
    AgentMsgPackagingContext(MPF_MSG_PACK, MTV_1_0, packForAgencyRoute = false)

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext

  override def executionContextProvider: ExecutionContextProvider = ecp

  /**
   * custom thread pool executor
   */
  override def futureWalletExecutionContext: ExecutionContext = ecp.walletFutureExecutionContext
}
