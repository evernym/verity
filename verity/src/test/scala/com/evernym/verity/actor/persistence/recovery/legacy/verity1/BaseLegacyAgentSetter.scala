package com.evernym.verity.actor.persistence.recovery.legacy.verity1

import com.evernym.verity.actor._
import com.evernym.verity.actor.persistence.recovery.base.{AgentIdentifiers, BasePersistentStore}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.constants.ActorNameConstants.{ACTOR_TYPE_AGENCY_AGENT_ACTOR, ACTOR_TYPE_USER_AGENT_ACTOR, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR}

//base traits for different type of agent actor setup

trait AgencyAgentEventSetter extends AgentIdentifiers with BasePersistentStore { this: ActorSpec =>

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
    KeyCreated(myAgencyAgentDIDPair.DID),
    EndpointSet()
  )
}

trait UserAgentEventSetter extends AgentIdentifiers with BasePersistentStore { this: ActorSpec =>

  def uaRegion: agentRegion = agentRegion(mySelfRelAgentEntityId, userAgentRegionActor)

  def setupBasicUserAgent(): Unit = {
    setupBasicUserAgentWalletData()
    addEventsToPersistentStorage(mySelfRelAgentPersistenceId, basicUserAgentEvents)
    storeAgentRoute(mySelfRelDIDPair.DID, ACTOR_TYPE_USER_AGENT_ACTOR, mySelfRelAgentEntityId)
    storeAgentRoute(mySelfRelAgentDIDPair.DID, ACTOR_TYPE_USER_AGENT_ACTOR, mySelfRelAgentEntityId)
  }

  protected lazy val basicUserAgentEvents = scala.collection.immutable.Seq(
    OwnerDIDSet(mySelfRelDIDPair.DID),
    AgentKeyCreated(mySelfRelAgentDIDPair.DID),
    ComMethodUpdated("push-token", 1, "firebase-push-token"),
    ComMethodUpdated("webhook", 2, "http://abc.xyz.com"),
    ConfigUpdated("name","name1", 1615697665879l),
    ConfigUpdated("logoUrl","/logo_url.ico",1615697665880l),

    //pairwise connection event for each new connection
    AgentDetailSet(myPairwiseRelDIDPair.DID, myPairwiseRelAgentDIDPair.DID, myPairwiseRelDIDPair.verKey, myPairwiseRelAgentDIDPair.verKey)
  )

  private def setupBasicUserAgentWalletData(): Unit = {
    createWallet(mySelfRelAgentEntityId)
    createNewKey(mySelfRelAgentEntityId, Option(mySelfRelAgentDIDKeySeed))
    storeTheirKey(mySelfRelAgentEntityId, mySelfRelDIDPair)
  }
}

trait UserAgentPairwiseEventSetter extends AgentIdentifiers with BasePersistentStore { this: ActorSpec =>

  val addTheirPairwiseKeyInWallet: Boolean = true

  def uapRegion: agentRegion = agentRegion(myPairwiseRelAgentEntityId, userAgentPairwiseRegionActor)

  def setupBasicUserAgentPairwise(): Unit = {
    setupBasicUserAgentPairwiseWalletData()
    storeUserAgentPairwiseEvents()
  }

  private def setupBasicUserAgentPairwiseWalletData(): Unit = {
    createNewKey(mySelfRelAgentEntityId, Option(myPairwiseRelAgentKeySeed))
    storeTheirKey(mySelfRelAgentEntityId, myPairwiseRelDIDPair)
    if (addTheirPairwiseKeyInWallet)
      storeTheirKey(mySelfRelAgentEntityId, theirPairwiseRelDIDPair)
    storeTheirKey(mySelfRelAgentEntityId, theirPairwiseRelAgentDIDPair)
    storeTheirKey(mySelfRelAgentEntityId, theirAgencyAgentDIDPair)
  }

  private def storeUserAgentPairwiseEvents(): Unit = {
    addEventsToPersistentStorage(myPairwiseRelAgentPersistenceId, basicUserAgentPairwiseEvents)
    storeAgentRoute(myPairwiseRelDIDPair.DID, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR, myPairwiseRelAgentEntityId)
    storeAgentRoute(myPairwiseRelAgentDIDPair.DID, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR, myPairwiseRelAgentEntityId)
  }

  protected lazy val basicUserAgentPairwiseEvents = scala.collection.immutable.Seq(
    OwnerSetForAgent(mySelfRelDIDPair.DID, mySelfRelAgentDIDPair.DID),
    AgentDetailSet(myPairwiseRelDIDPair.DID, myPairwiseRelAgentDIDPair.DID),
    AgentKeyDlgProofSet(myPairwiseRelAgentDIDPair.DID, myPairwiseRelAgentDIDPair.verKey,"dummy-signature"),
    MsgCreated("001","connReq",myPairwiseRelDIDPair.DID,"MS-101",1548446192302L,1548446192302L,"",None),
    MsgCreated("002","connReqAnswer",theirPairwiseRelDIDPair.DID,"MS-104",1548446192302L,1548446192302L,"",None),
    MsgAnswered("001","MS-104","002",1548446192302L),
    TheirAgentDetailSet(theirPairwiseRelDIDPair.DID, theirPairwiseRelAgentDIDPair.DID),
    TheirAgentKeyDlgProofSet(theirPairwiseRelAgentDIDPair.DID, theirPairwiseRelAgentDIDPair.verKey,"dummy-signature"),
    TheirAgencyIdentitySet(theirAgencyAgentDIDPair.DID, theirAgencyAgentDIDPair.verKey,"0.0.0.1:9000/agency/msg")
  )

}
