package com.evernym.verity.actor.persistence.recovery.base

import com.evernym.verity.actor.{AgentDetailSet, AgentKeyDlgProofSet, MsgAnswered, MsgCreated, OwnerSetForAgent, TheirAgencyIdentitySet, TheirAgentDetailSet, TheirAgentKeyDlgProofSet}
import com.evernym.verity.constants.ActorNameConstants._

trait AgentEventSetter extends BasePersistentStore {

  val thisAgencyAgentEntityId             = "000"
  val selfRelAgentEntityId                = "111"    //entity id of UserAgent actor
  val myPairwiseRelAgentEntityId          = "222"    //entity id of UserAgentPairwise actor

  val thisAgencyAgentDIDKeySeed           = "00000000000000000000000000000000"
  val selfRelDIDKeySeed                   = "00000000000000000000000000000002"
  val selfRelAgentDIDKeySeed              = "00000000000000000000000000000003"
  val myPairwiseRelDIDKeySeed             = "00000000000000000000000000000004"
  val myPairwiseRelAgentKeySeed           = "00000000000000000000000000000005"
  val theirPairwiseRelDIDKeySeed          = "00000000000000000000000000000006"
  val theirPairwiseAgentDIDKeySeed        = "00000000000000000000000000000007"
  val theirAgencyDIDKeySeed               = "00000000000000000000000000000008"

  val selfRelAgentPersistenceId           = PersistenceIdParam(USER_AGENT_REGION_ACTOR_NAME, selfRelAgentEntityId)
  val myPairwiseRelAgentPersistenceId     = PersistenceIdParam(USER_AGENT_PAIRWISE_REGION_ACTOR_NAME, myPairwiseRelAgentEntityId)

  lazy val thisAgencyAgentDIDPair         = generateNewDid(Option(thisAgencyAgentDIDKeySeed))
  lazy val mySelfRelDIDPair               = generateNewDid(Option(selfRelDIDKeySeed))
  lazy val mySelfRelAgentDIDPair          = generateNewDid(Option(selfRelAgentDIDKeySeed))
  lazy val myPairwiseRelDIDPair           = generateNewDid(Option(myPairwiseRelDIDKeySeed))
  lazy val myPairwiseRelAgentDIDPair      = generateNewDid(Option(myPairwiseRelAgentKeySeed))
  lazy val theirPairwiseRelDIDPair        = generateNewDid(Option(theirPairwiseRelDIDKeySeed))
  lazy val theirPairwiseRelAgentDIDPair   = generateNewDid(Option(theirPairwiseAgentDIDKeySeed))
  lazy val theirAgencyAgentDIDPair        = generateNewDid(Option(theirAgencyDIDKeySeed))

  def setupBasicAgencyAgent(): Unit = {
    //NOTE: set up more events for agency agent
    storeAgencyDIDKeyValueMapping(thisAgencyAgentDIDPair.DID)
  }

  def setupBasicUserAgent(): Unit = {
    setupBasicUserAgentWalletData()
    //NOTE: add more events for basic setup of user agent actor here
    storeAgentRoute(mySelfRelAgentDIDPair.DID, ACTOR_TYPE_USER_AGENT_ACTOR, selfRelAgentEntityId)
  }

  private def setupBasicUserAgentWalletData(): Unit = {
    createWallet(selfRelAgentEntityId)
    createNewKey(selfRelAgentEntityId, Option(selfRelDIDKeySeed))
    createNewKey(selfRelAgentEntityId, Option(selfRelAgentDIDKeySeed))
  }

  def setupBasicUserAgentPairwiseWithLegacyEvents(): Unit = {
    setupBasicUserAgentPairwiseWalletData()
    storeUserAgentPairwiseEvents()
  }

  private def setupBasicUserAgentPairwiseWalletData(): Unit = {
    createNewKey(selfRelAgentEntityId, Option(myPairwiseRelDIDKeySeed))
    createNewKey(selfRelAgentEntityId, Option(myPairwiseRelAgentKeySeed))
    createNewKey(selfRelAgentEntityId, Option(theirPairwiseRelDIDKeySeed))
    createNewKey(selfRelAgentEntityId, Option(theirPairwiseAgentDIDKeySeed))
    createNewKey(selfRelAgentEntityId, Option(theirAgencyDIDKeySeed))
  }

  private def storeUserAgentPairwiseEvents(): Unit = {
    addEventsToPersistentStorage(myPairwiseRelAgentPersistenceId, basicEvents)
    storeAgentRoute(myPairwiseRelAgentDIDPair.DID, ACTOR_TYPE_USER_AGENT_ACTOR, myPairwiseRelAgentEntityId)
  }

  lazy val basicEvents = scala.collection.immutable.Seq(
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
