package com.evernym.verity.actor.persistence.recovery.latest.verity1

import com.evernym.verity.actor._
import com.evernym.verity.actor.persistence.recovery.base.{AgentIdentifiers, BasePersistentStore}
import com.evernym.verity.constants.ActorNameConstants.{ACTOR_TYPE_AGENCY_AGENT_ACTOR, ACTOR_TYPE_USER_AGENT_ACTOR, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR}

//base traits for different type of agent actor setup

trait AgencyAgentEventSetter extends AgentIdentifiers { this: BasePersistentStore =>

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

trait UserAgentEventSetter extends AgentIdentifiers { this: BasePersistentStore =>

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
    ComMethodUpdated("1", 2, "http://abc.xyz.com"),
    ComMethodUpdated("2", 2, "http://abc.xyz.com", Option(ComMethodPackaging("plain", Seq(mySelfRelDIDPair.verKey))))
  )

  private def setupBasicUserAgentWalletData(): Unit = {
    createWallet(mySelfRelAgentEntityId)
    createNewKey(mySelfRelAgentEntityId, Option(mySelfRelDIDKeySeed))
    createNewKey(mySelfRelAgentEntityId, Option(mySelfRelAgentDIDKeySeed))
  }
}

trait UserAgentPairwiseEventSetter extends AgentIdentifiers { this: BasePersistentStore =>

  def uapRegion: agentRegion = agentRegion(myPairwiseRelAgentEntityId, userAgentPairwiseRegionActor)

  def setupBasicUserAgentPairwise(): Unit = {
    setupBasicUserAgentPairwiseWalletData()
    storeUserAgentPairwiseEvents()
  }

  private def setupBasicUserAgentPairwiseWalletData(): Unit = {
    createNewKey(mySelfRelAgentEntityId, Option(myPairwiseRelDIDKeySeed))
    createNewKey(mySelfRelAgentEntityId, Option(myPairwiseRelAgentKeySeed))
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
    OwnerSetForAgent(mySelfRelDIDPair.DID, mySelfRelAgentDIDPair.DID, mySelfRelAgentDIDPair.verKey),
    AgentDetailSet(myPairwiseRelDIDPair.DID, myPairwiseRelAgentDIDPair.DID, myPairwiseRelDIDPair.verKey, myPairwiseRelAgentDIDPair.verKey),
    ConnectionStatusUpdated(
      reqReceived = true,
      answerStatusCode = "MS-104",
      Option(TheirDidDocDetail(
        theirPairwiseRelDIDPair.DID,
        theirAgencyAgentDIDPair.DID,
        theirPairwiseRelAgentDIDPair.DID,
        theirPairwiseRelAgentDIDPair.verKey,
        "dummy-signature",
        pairwiseDIDVerKey = theirPairwiseRelDIDPair.verKey
      ))
    )
  )

}
