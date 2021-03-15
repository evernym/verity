package com.evernym.verity.actor.persistence.recovery.base

import java.util.UUID

import com.evernym.verity.actor.AuthKeyAdded
import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.actor.testkit.CommonSpecUtil._

trait AgentIdentifiers {

  lazy val myAgencyAgentDIDKeySeed = randomSeed()
  lazy val mySelfRelDIDKeySeed = randomSeed()
  lazy val mySelfRelAgentDIDKeySeed = randomSeed()
  lazy val requesterDIDKeySeed = randomSeed()
  lazy val myPairwiseRelDIDKeySeed = randomSeed()
  lazy val myPairwiseRelAgentKeySeed = randomSeed()
  lazy val theirPairwiseRelDIDKeySeed = randomSeed()
  lazy val theirPairwiseAgentDIDKeySeed = randomSeed()
  lazy val theirAgencyDIDKeySeed = randomSeed()

  lazy val myAgencyAgentDIDPair = generateNewDid(myAgencyAgentDIDKeySeed)
  lazy val mySelfRelDIDPair = generateNewDid(mySelfRelDIDKeySeed)
  lazy val mySelfRelAgentDIDPair = generateNewDid(mySelfRelAgentDIDKeySeed)
  lazy val requesterDIDPair = generateNewDid(requesterDIDKeySeed)
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

  def getAuthKeyAddedEvents(dp: DidPair): List[AuthKeyAdded] =
    getAuthKeyAddedEvents(List(dp))

  def getAuthKeyAddedEvents(dps: List[DidPair]): List[AuthKeyAdded] = {
    dps.map { dp =>
      AuthKeyAdded(dp.DID, dp.verKey)
    }
  }
}