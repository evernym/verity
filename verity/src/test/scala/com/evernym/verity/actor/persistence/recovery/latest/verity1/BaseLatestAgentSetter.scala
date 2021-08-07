package com.evernym.verity.actor.persistence.recovery.latest.verity1

import com.evernym.verity.actor._
import com.evernym.verity.actor.persistence.recovery.base.{AgentIdentifiers, BasePersistentStore}
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.constants.ActorNameConstants.{ACTOR_TYPE_AGENCY_AGENT_ACTOR, ACTOR_TYPE_USER_AGENT_ACTOR, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR}

//base traits for different type of agent actor setup

trait AgencyAgentEventSetter extends AgentIdentifiers with BasePersistentStore { this: ProvidesMockPlatform =>

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
    KeyCreated(myAgencyAgentDIDPair.did, myAgencyAgentDIDPair.verKey),
    EndpointSet()
  )
}

trait UserAgentEventSetter extends AgentIdentifiers with BasePersistentStore { this: ProvidesMockPlatform =>

  def uaRegion: agentRegion = agentRegion(mySelfRelAgentEntityId, userAgentRegionActor)

  def setupBasicUserAgent(): Unit = {
    setupBasicUserAgentWalletData()
    addEventsToPersistentStorage(mySelfRelAgentPersistenceId, basicUserAgentEvents)
    storeAgentRoute(mySelfRelDIDPair.did, ACTOR_TYPE_USER_AGENT_ACTOR, mySelfRelAgentEntityId)
    storeAgentRoute(mySelfRelAgentDIDPair.did, ACTOR_TYPE_USER_AGENT_ACTOR, mySelfRelAgentEntityId)
  }

  protected lazy val basicUserAgentEvents = scala.collection.immutable.Seq(
    OwnerDIDSet(mySelfRelDIDPair.did, mySelfRelDIDPair.verKey),
    AgentKeyCreated(mySelfRelAgentDIDPair.did, mySelfRelAgentDIDPair.verKey),
    ComMethodUpdated("1", 2, "http://abc.xyz.com"),
    ComMethodUpdated("2", 2, "http://abc.xyz.com", Option(ComMethodPackaging("plain", Seq(mySelfRelDIDPair.verKey))))
  )

  private def setupBasicUserAgentWalletData(): Unit = {
    createWallet(mySelfRelAgentEntityId)
    createNewKey(mySelfRelAgentEntityId, Option(mySelfRelDIDKeySeed))
    createNewKey(mySelfRelAgentEntityId, Option(mySelfRelAgentDIDKeySeed))
  }
}

trait UserAgentPairwiseEventSetter extends AgentIdentifiers with BasePersistentStore { this: ProvidesMockPlatform =>

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
    storeAgentRoute(myPairwiseRelDIDPair.did, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR, myPairwiseRelAgentEntityId)
    storeAgentRoute(myPairwiseRelAgentDIDPair.did, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR, myPairwiseRelAgentEntityId)
  }

  protected lazy val basicUserAgentPairwiseEvents = scala.collection.immutable.Seq(
    OwnerSetForAgent(mySelfRelDIDPair.did, mySelfRelAgentDIDPair.did, mySelfRelAgentDIDPair.verKey),
    AgentDetailSet(myPairwiseRelDIDPair.did, myPairwiseRelAgentDIDPair.did, myPairwiseRelDIDPair.verKey, myPairwiseRelAgentDIDPair.verKey),
    ConnectionStatusUpdated(
      reqReceived = true,
      answerStatusCode = "MS-104",
      Option(TheirDidDocDetail(
        theirPairwiseRelDIDPair.did,
        theirAgencyAgentDIDPair.did,
        theirPairwiseRelAgentDIDPair.did,
        theirPairwiseRelAgentDIDPair.verKey,
        "dummy-signature",
        pairwiseDIDVerKey = theirPairwiseRelDIDPair.verKey
      ))
    )
  )

}
