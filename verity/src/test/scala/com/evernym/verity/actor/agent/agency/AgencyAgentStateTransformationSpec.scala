package com.evernym.verity.actor.agent.agency

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.agent.MsgPackFormat.{MPF_INDY_PACK, MPF_MSG_PACK}
import com.evernym.verity.actor.agent.TypeFormat.{LEGACY_TYPE_FORMAT, STANDARD_TYPE_FORMAT}
import com.evernym.verity.actor.agent.relationship.Tags.EDGE_AGENT_KEY
import com.evernym.verity.actor.agent.relationship._
import com.evernym.verity.actor.agent.{DidPair, MsgOrders, ProtocolRunningInstances, ThreadContext, ThreadContextDetail}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningDefinition
import com.evernym.verity.protocol.protocols.connecting.v_0_5.ConnectingProtoDef
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.transformations.transformers.v1._

class AgencyAgentStateTransformationSpec extends ActorSpec with BasicSpec {

  lazy val transformer = createPersistenceTransformerV1("enc key", appConfig)

  "AgencyAgentState" - {

    "with new persistence transformer" - {
      "should be able to serialize/transform and deserialize/untransform successfully" in {

        val originalState = createAgencyAgentState()
        val serializedState = transformer.execute(originalState)
        val deserializedState = transformer.undo(serializedState).asInstanceOf[AgencyAgentState]

        //asserts that original State and deserialized state are equals
        originalState.agencyDIDPair shouldBe deserializedState.agencyDIDPair
        originalState.isEndpointSet shouldBe deserializedState.isEndpointSet
        originalState.agentWalletId shouldBe deserializedState.agentWalletId
        originalState.thisAgentKeyId shouldBe deserializedState.thisAgentKeyId

        List("pinst-id-1", "pinst-id-2").foreach { pinstId =>
          val originalStateProtoInstances = originalState.protoInstances.get
          val deserializedStateProtoInstances = deserializedState.protoInstances.get
          val originalStateThreadContext = originalState.threadContext.get
          val deserializedStateThreadContext = deserializedState.threadContext.get

          originalStateProtoInstances.instances.get(ConnectingProtoDef.toString) shouldBe
            deserializedStateProtoInstances.instances.get(ConnectingProtoDef.toString)
          originalStateProtoInstances.instances.get(AgentProvisioningDefinition.toString) shouldBe
            deserializedStateProtoInstances.instances.get(AgentProvisioningDefinition.toString)
          originalStateThreadContext.contexts.get(pinstId) shouldBe
            deserializedStateThreadContext.contexts.get(pinstId)
        }
        originalState.relationship shouldBe deserializedState.relationship
       }
    }
  }

  def createAgencyAgentState(): AgencyAgentState = {

    def relationship: Relationship = AnywiseRelationship(Option(myDidDoc))

    def myDidDoc: DidDoc = {
      DidDoc(
        "did1",
        Option(AuthorizedKeys(Seq(AuthorizedKey("key1", "", Set(EDGE_AGENT_KEY))))),
        //agency agent won't have below type of endpoints, we are just using it to test
        //if it serializes/deserializes endpoints successfully or not
        Option(Endpoints.init(Seq(RoutingServiceEndpoint("1", Seq("key1")))))
      )
    }

    def threadContext: ThreadContext = ThreadContext(
      Map(
        "pinst-id-1" ->
          ThreadContextDetail (
            "thread-id-1",
            MPF_INDY_PACK,
            STANDARD_TYPE_FORMAT,
            usesLegacyGenMsgWrapper = true,
            usesLegacyBundledMsgWrapper = true,
            msgOrders = Option(MsgOrders(
              senderOrder = 1,
              receivedOrders = Map("participant-1" -> 2))
            )
          ),
        "pinst-id-2" ->
          ThreadContextDetail (
            "thread-id-1",
            MPF_MSG_PACK,
            LEGACY_TYPE_FORMAT,
            msgOrders = Option(MsgOrders(
              senderOrder = 2,
              receivedOrders = Map("participant-2" -> 3))
            )
          )
      )
    )

    def protoInstances = ProtocolRunningInstances(
      Map(
        ConnectingProtoDef.msgFamily.protoRef.toString -> "pinst-id-1",
        AgentProvisioningDefinition.msgFamily.protoRef.toString -> "pinst-id-2"
      )
    )

    new AgencyAgentState()
      .withIsEndpointSet(true)
      .withAgencyDIDPair(DidPair("agency-did", "agency-did-verkey"))
      .withThisAgentKeyId("this-agent-key-1")
      .withAgentWalletId("wallet-id")
      .withThreadContext(threadContext)
      .withProtoInstances(protoInstances)
      .withRelationship(relationship)
  }
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}
