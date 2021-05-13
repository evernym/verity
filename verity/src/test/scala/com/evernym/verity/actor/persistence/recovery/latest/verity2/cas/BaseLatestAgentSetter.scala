package com.evernym.verity.actor.persistence.recovery.latest.verity2.cas

import java.util.UUID
import com.evernym.verity.actor._
import com.evernym.verity.actor.persistence.recovery.base.{AgentIdentifiers, BasePersistentStore}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.constants.ActorNameConstants.{ACTOR_TYPE_AGENCY_AGENT_ACTOR, ACTOR_TYPE_USER_AGENT_ACTOR, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR}
import com.google.protobuf.ByteString

//base traits for different type of agent actor setup

trait AgencyAgentEventSetter extends AgentIdentifiers with BasePersistentStore { this: ProvidesMockPlatform =>

  def aaRegion: agentRegion = agentRegion(myAgencyAgentEntityId, agencyAgentRegion)

  def setupBasicAgencyAgent(): Unit = {
    //TODO: if we move below line 'setting of key value mapper' to any other
    // position in this method, ideally it should work but for some reason it doesn't, should find out why?
    storeAgencyDIDKeyValueMapping(myAgencyAgentDIDPair.DID)
    setupBasicAgencyAgentWalletData()
    addEventsToPersistentStorage(myAgencyAgentPersistenceId, basicAgencyAgentEvents)
    storeAgentRoute(myAgencyAgentDIDPair.DID, ACTOR_TYPE_AGENCY_AGENT_ACTOR, myAgencyAgentEntityId)
  }

  private def setupBasicAgencyAgentWalletData(): Unit = {
    createWallet(myAgencyAgentEntityId)
    createNewKey(myAgencyAgentEntityId, Option(myAgencyAgentDIDKeySeed))
  }

  protected lazy val basicAgencyAgentEvents = scala.collection.immutable.Seq(
    KeyCreated(myAgencyAgentDIDPair.DID, myAgencyAgentDIDPair.verKey),
    EndpointSet()
  )
}

trait UserAgentEventSetter extends AgentIdentifiers with BasePersistentStore { this: ProvidesMockPlatform =>

  def uaRegion: agentRegion = agentRegion(mySelfRelAgentEntityId, userAgentRegionActor)

  def setupBasicUserAgent(): Unit = {
    setupBasicUserAgentWalletData()
    addEventsToPersistentStorage(mySelfRelAgentPersistenceId, basicUserAgentEvents)
    storeAgentRoute(mySelfRelDIDPair.DID, ACTOR_TYPE_USER_AGENT_ACTOR, mySelfRelAgentEntityId)
    storeAgentRoute(mySelfRelAgentDIDPair.DID, ACTOR_TYPE_USER_AGENT_ACTOR, mySelfRelAgentEntityId)
  }

  protected lazy val basicUserAgentEvents = scala.collection.immutable.Seq(
    OwnerDIDSet(mySelfRelDIDPair.DID, mySelfRelDIDPair.verKey),
    AgentKeyCreated(mySelfRelAgentDIDPair.DID, mySelfRelAgentDIDPair.verKey),
    ComMethodUpdated("push-token", 1, "firebase-push-token"),

    //pairwise connection event for each new connection
    AgentDetailSet(myPairwiseRelDIDPair.DID, myPairwiseRelAgentDIDPair.DID, myPairwiseRelDIDPair.verKey, myPairwiseRelAgentDIDPair.verKey)
  )

  private def setupBasicUserAgentWalletData(): Unit = {
    createWallet(mySelfRelAgentEntityId)
    createNewKey(mySelfRelAgentEntityId, Option(mySelfRelAgentDIDKeySeed))
    storeTheirKey(mySelfRelAgentEntityId, mySelfRelDIDPair)
  }
}

trait UserAgentPairwiseEventSetter extends AgentIdentifiers with BasePersistentStore { this: ProvidesMockPlatform =>

  def uapRegion: agentRegion = agentRegion(myPairwiseRelAgentEntityId, userAgentPairwiseRegionActor)

  def setupBasicUserAgentPairwise(): Unit = {
    setupBasicUserAgentPairwiseWalletData()
    storeUserAgentPairwiseEvents()
  }

  private def setupBasicUserAgentPairwiseWalletData(): Unit = {
    createNewKey(mySelfRelAgentEntityId, Option(myPairwiseRelAgentKeySeed))
    storeTheirKey(mySelfRelAgentEntityId, myPairwiseRelDIDPair)
  }

  private def storeUserAgentPairwiseEvents(): Unit = {
    addEventsToPersistentStorage(myPairwiseRelAgentPersistenceId, basicUserAgentPairwiseEvents)
    storeAgentRoute(myPairwiseRelDIDPair.DID, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR, myPairwiseRelAgentEntityId)
    storeAgentRoute(myPairwiseRelAgentDIDPair.DID, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR, myPairwiseRelAgentEntityId)
  }

  val responseMsgId = UUID.randomUUID().toString
  val oobAcceptedMsgId = UUID.randomUUID().toString

  protected lazy val basicUserAgentPairwiseEvents = scala.collection.immutable.Seq(
    ProtocolIdDetailSet("connecting","0.6","connecting-pinst-id"),
    OwnerSetForAgent(mySelfRelDIDPair.DID, mySelfRelAgentDIDPair.DID, mySelfRelAgentDIDPair.verKey),
    AgentDetailSet(myPairwiseRelDIDPair.DID, myPairwiseRelAgentDIDPair.DID, myPairwiseRelDIDPair.verKey, myPairwiseRelAgentDIDPair.verKey),
    // for aries connection on CAS, there shouldn't be any connection related events in this actor

    //TODO: 'senderDID' in these events looks wrong (it should be from theirPairwiseRelDIDPair.DID)
    // FYI: this issue is in the main code, not in these events setup done in the test
    MsgCreated(responseMsgId,"did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/response",myPairwiseRelDIDPair.DID,
      "MS-103",1615697700836l,1615697700836l,"",None,true),
    MsgPayloadStored(responseMsgId,ByteString.copyFromUtf8("response"),None),
    MsgStatusUpdated(responseMsgId,"MS-106",1615697702731l),

    MsgCreated(oobAcceptedMsgId,"did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/out-of-band/1.0/handshake-reuse-accepted",
      myPairwiseRelDIDPair.DID,"MS-103",1615697720549l,1615697720549l,"",None,true),
    MsgPayloadStored(oobAcceptedMsgId,ByteString.copyFromUtf8("handshake-reuse-accepted"),None),
  )

}
