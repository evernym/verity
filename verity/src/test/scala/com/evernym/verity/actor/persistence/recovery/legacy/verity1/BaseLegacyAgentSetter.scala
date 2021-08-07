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
    storeAgencyDIDKeyValueMapping(myAgencyAgentDIDPair.did)
    setupBasicAgencyAgentWalletData()
    addEventsToPersistentStorage(myAgencyAgentPersistenceId, basicAgencyAgentEvents)
    storeAgentRoute(myAgencyAgentDIDPair.did, ACTOR_TYPE_AGENCY_AGENT_ACTOR, myAgencyAgentEntityId)
  }

  private def setupBasicAgencyAgentWalletData(): Unit = {
    createWallet(myAgencyAgentEntityId)
    createNewKey(myAgencyAgentEntityId, Option(myAgencyAgentDIDKeySeed))
  }

  protected lazy val basicAgencyAgentEvents = scala.collection.immutable.Seq(
    KeyCreated(myAgencyAgentDIDPair.did),
    EndpointSet()
  )
}

trait UserAgentEventSetter extends AgentIdentifiers with BasePersistentStore { this: ActorSpec =>

  def uaRegion: agentRegion = agentRegion(mySelfRelAgentEntityId, userAgentRegionActor)

  def setupBasicUserAgent(): Unit = {
    setupBasicUserAgentWalletData()
    addEventsToPersistentStorage(mySelfRelAgentPersistenceId, basicUserAgentEvents)
    storeAgentRoute(mySelfRelDIDPair.did, ACTOR_TYPE_USER_AGENT_ACTOR, mySelfRelAgentEntityId)
    storeAgentRoute(mySelfRelAgentDIDPair.did, ACTOR_TYPE_USER_AGENT_ACTOR, mySelfRelAgentEntityId)
  }

  protected lazy val basicUserAgentEvents = scala.collection.immutable.Seq(
    OwnerDIDSet(mySelfRelDIDPair.did),
    AgentKeyCreated(mySelfRelAgentDIDPair.did),
    ComMethodUpdated("push-token", 1, "firebase-push-token"),
    ComMethodUpdated("webhook", 2, "http://abc.xyz.com"),
    ConfigUpdated("name","name1", 1615697665879l),
    ConfigUpdated("logoUrl","/logo_url.ico",1615697665880l),

    //pairwise connection event for each new connection
    AgentDetailSet(myPairwiseRelDIDPair.did, myPairwiseRelAgentDIDPair.did, myPairwiseRelDIDPair.verKey, myPairwiseRelAgentDIDPair.verKey)
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
    storeAgentRoute(myPairwiseRelDIDPair.did, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR, myPairwiseRelAgentEntityId)
    storeAgentRoute(myPairwiseRelAgentDIDPair.did, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR, myPairwiseRelAgentEntityId)
  }

  protected lazy val basicUserAgentPairwiseEvents = scala.collection.immutable.Seq(
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

}
