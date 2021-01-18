package com.evernym.verity.actor.persistence.recovery.base

import java.util.UUID

import com.evernym.verity.actor.{AgentDetailSet, AgentKeyCreated, AgentKeyDlgProofSet, EndpointSet, KeyCreated, MsgAnswered, MsgCreated, OwnerDIDSet, OwnerSetForAgent, TheirAgencyIdentitySet, TheirAgentDetailSet, TheirAgentKeyDlgProofSet, agentRegion}
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.actor.testkit.CommonSpecUtil._


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
    KeyCreated(myAgencyAgentDIDPair.DID),
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
    OwnerDIDSet(mySelfRelDIDPair.DID),
    AgentKeyCreated(mySelfRelAgentDIDPair.DID),
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
    createNewKey(mySelfRelAgentEntityId, Option(theirPairwiseRelDIDKeySeed))
    createNewKey(mySelfRelAgentEntityId, Option(theirPairwiseAgentDIDKeySeed))
    createNewKey(mySelfRelAgentEntityId, Option(theirAgencyDIDKeySeed))
  }

  private def storeUserAgentPairwiseEvents(): Unit = {
    addEventsToPersistentStorage(myPairwiseRelAgentPersistenceId, basicUserAgentPairwiseEvents)
    storeAgentRoute(myPairwiseRelDIDPair.DID, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR, myPairwiseRelAgentEntityId)
    storeAgentRoute(myPairwiseRelAgentDIDPair.DID, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR, myPairwiseRelAgentEntityId)
  }

  protected lazy val basicUserAgentPairwiseEvents = scala.collection.immutable.Seq(
    OwnerSetForAgent(mySelfRelDIDPair.DID, mySelfRelAgentDIDPair.DID),
    AgentDetailSet(myPairwiseRelDIDPair.DID,myPairwiseRelAgentDIDPair.DID),
    AgentKeyDlgProofSet(myPairwiseRelAgentDIDPair.DID, myPairwiseRelAgentDIDPair.verKey,"dummy-signature"),
    MsgCreated("001","connReq",myPairwiseRelDIDPair.DID,"MS-101",1548446192302L,1548446192302L,"",None),
    MsgCreated("002","connReqAnswer",theirPairwiseRelDIDPair.DID,"MS-104",1548446192302L,1548446192302L,"",None),
    MsgAnswered("001","MS-104","002",1548446192302L),
    TheirAgentDetailSet(theirPairwiseRelDIDPair.DID, theirPairwiseRelAgentDIDPair.DID),
    TheirAgentKeyDlgProofSet(theirPairwiseRelAgentDIDPair.DID, theirPairwiseRelAgentDIDPair.verKey,"dummy-signature"),
    TheirAgencyIdentitySet(theirAgencyAgentDIDPair.DID, theirAgencyAgentDIDPair.verKey,"0.0.0.1:9000/agency/msg")
  )

}

trait AgentIdentifiers {

  lazy val myAgencyAgentDIDKeySeed = randomSeed()
  lazy val mySelfRelDIDKeySeed = randomSeed()
  lazy val mySelfRelAgentDIDKeySeed = randomSeed()
  lazy val myPairwiseRelDIDKeySeed = randomSeed()
  lazy val myPairwiseRelAgentKeySeed = randomSeed()
  lazy val theirPairwiseRelDIDKeySeed = randomSeed()
  lazy val theirPairwiseAgentDIDKeySeed = randomSeed()
  lazy val theirAgencyDIDKeySeed = randomSeed()

  lazy val myAgencyAgentDIDPair = generateNewDid(myAgencyAgentDIDKeySeed)
  lazy val mySelfRelDIDPair = generateNewDid(mySelfRelDIDKeySeed)
  lazy val mySelfRelAgentDIDPair = generateNewDid(mySelfRelAgentDIDKeySeed)
  lazy val myPairwiseRelDIDPair = generateNewDid(myPairwiseRelDIDKeySeed)
  lazy val myPairwiseRelAgentDIDPair = generateNewDid(myPairwiseRelAgentKeySeed)
  lazy val theirPairwiseRelDIDPair = generateNewDid(theirPairwiseRelDIDKeySeed)
  lazy val theirPairwiseRelAgentDIDPair = generateNewDid(theirPairwiseAgentDIDKeySeed)
  lazy val theirAgencyAgentDIDPair = generateNewDid(theirAgencyDIDKeySeed)

  lazy val myAgencyAgentEntityId = randomUUID() //entity id of AgencyAgent actor
  lazy val mySelfRelAgentEntityId = randomUUID() //entity id of UserAgent actor
  lazy val myPairwiseRelAgentEntityId = randomUUID() //entity id of UserAgentPairwise actor

  lazy val myAgencyAgentPersistenceId = PersistenceIdParam(AGENCY_AGENT_REGION_ACTOR_NAME, myAgencyAgentEntityId)
  lazy val mySelfRelAgentPersistenceId = PersistenceIdParam(USER_AGENT_REGION_ACTOR_NAME, mySelfRelAgentEntityId)
  lazy val myPairwiseRelAgentPersistenceId = PersistenceIdParam(USER_AGENT_PAIRWISE_REGION_ACTOR_NAME, myPairwiseRelAgentEntityId)

  def randomUUID(): String = UUID.randomUUID().toString
  def randomSeed(): String = randomUUID().replace("-", "")
}