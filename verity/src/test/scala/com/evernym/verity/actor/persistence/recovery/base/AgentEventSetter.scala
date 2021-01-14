package com.evernym.verity.actor.persistence.recovery.base

import com.evernym.verity.actor.{AgentDetailSet, AgentKeyDlgProofSet, MsgAnswered, MsgCreated, OwnerSetForAgent, TheirAgencyIdentitySet, TheirAgentDetailSet, TheirAgentKeyDlgProofSet}
import com.evernym.verity.constants.ActorNameConstants.{ACTOR_TYPE_USER_AGENT_ACTOR, KEY_VALUE_MAPPER_ACTOR_NAME}

trait AgentEventSetter extends BasePersistentStore {

  val thisAgencyAgentEntityId             = "000"
  val selfRelAgentEntityId                = "111"    //entity id of UserAgent actor
  val myPairwiseRelAgentEntityId          = "222"    //entity id of UserAgentPairwise actor
  val userAgentPairwisePersistenceId      = s"UserAgentPairwise-$myPairwiseRelAgentEntityId"

  val thisAgencyAgentDIDKeySeed           = "00000000000000000000000000000000"
  val selfRelDIDKeySeed                   = "00000000000000000000000000000002"
  val selfRelAgentDIDKeySeed              = "00000000000000000000000000000003"
  val myPairwiseRelDIDKeySeed             = "00000000000000000000000000000004"
  val myPairwiseRelAgentKeySeed           = "00000000000000000000000000000005"
  val theirPairwiseRelDIDKeySeed          = "00000000000000000000000000000006"
  val theirPairwiseAgentDIDKeySeed        = "00000000000000000000000000000007"
  val theirAgencyDIDKeySeed               = "00000000000000000000000000000008"

  lazy val thisAgencyAgentDIDPair         = generateNewDid(Option(thisAgencyAgentDIDKeySeed))
  lazy val mySelfRelDIDPair               = generateNewDid(Option(selfRelDIDKeySeed))
  lazy val mySelfRelAgentDIDPair          = generateNewDid(Option(selfRelAgentDIDKeySeed))
  lazy val myPairwiseRelDIDPair           = generateNewDid(Option(myPairwiseRelDIDKeySeed))
  lazy val myPairwiseRelAgentDIDPair      = generateNewDid(Option(myPairwiseRelAgentKeySeed))
  lazy val theirPairwiseRelDIDPair        = generateNewDid(Option(theirPairwiseRelDIDKeySeed))
  lazy val theirPairwiseRelAgentDIDPair   = generateNewDid(Option(theirPairwiseAgentDIDKeySeed))
  lazy val theirAgencyAgentDIDPair        = generateNewDid(Option(theirAgencyDIDKeySeed))

  def setupBasicAgencyAgent(): Unit = {
    //TODO: set up more events for agency agent
    storeAgencyDIDKeyValueMapping(thisAgencyAgentDIDPair.DID)(PersistParam.default(KEY_VALUE_MAPPER_ACTOR_NAME))
  }

  def setupBasicUserAgent(): Unit = {
    setupBasicUserAgentWalletData()
    //TODO: add more events for user agent actor here
    storeAgentRoute(mySelfRelAgentDIDPair.DID, ACTOR_TYPE_USER_AGENT_ACTOR, selfRelAgentEntityId)(PersistParam.default(selfRelAgentEntityId))
  }

  def setupBasicUserAgentWalletData(): Unit = {
    createWallet(selfRelAgentEntityId)
    createNewKey(selfRelAgentEntityId, Option(selfRelDIDKeySeed))
    createNewKey(selfRelAgentEntityId, Option(selfRelAgentDIDKeySeed))
  }

  def setupBasicUserAgentPairwiseWithLegacyEvents(): Unit = {
    setupBasicUserAgentPairwiseWalletData()
    storeUserAgentPairwiseEvents()
  }

  def setupBasicUserAgentPairwiseWalletData(): Unit = {
    createNewKey(selfRelAgentEntityId, Option(myPairwiseRelDIDKeySeed))
    createNewKey(selfRelAgentEntityId, Option(myPairwiseRelAgentKeySeed))
    createNewKey(selfRelAgentEntityId, Option(theirPairwiseRelDIDKeySeed))
    createNewKey(selfRelAgentEntityId, Option(theirPairwiseAgentDIDKeySeed))
    createNewKey(selfRelAgentEntityId, Option(theirAgencyDIDKeySeed))
  }

  private def storeUserAgentPairwiseEvents(): Unit = {
    addEventsToPersistentStorage(userAgentPairwisePersistenceId, basicEvents)(PersistParam.default(myPairwiseRelAgentEntityId))
    storeAgentRoute(myPairwiseRelAgentDIDPair.DID, ACTOR_TYPE_USER_AGENT_ACTOR, myPairwiseRelAgentEntityId)(PersistParam.default(myPairwiseRelAgentEntityId))
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
