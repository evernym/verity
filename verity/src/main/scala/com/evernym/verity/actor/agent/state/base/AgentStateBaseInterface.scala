package com.evernym.verity.actor.agent.state.base

import com.evernym.verity.actor.State
import com.evernym.verity.actor.agent.relationship.Tags.{AGENT_KEY_TAG, OWNER_AGENT_KEY}
import com.evernym.verity.actor.agent.relationship.{AuthorizedKey, AuthorizedKeyLike, DidDoc, KeyId, Relationship}
import com.evernym.verity.actor.agent.{ConnectionStatus, DidPair, ProtocolRunningInstances, ThreadContext, ThreadContextDetail}
import com.evernym.verity.did.{DID, VerKey}
import com.evernym.verity.protocol.engine._

/**
 * interface for agent's common state update (for self relationship actors)
 * agent common/base classes will call below function to update agent's state
 */
trait AgentStateUpdateInterface {
  type StateType <: AgentStateInterface
  def state: StateType

  def setAgentWalletId(walletId: String): Unit
  def setAgencyDIDPair(didPair: DidPair): Unit
  def addThreadContextDetail(threadContext: ThreadContext): Unit
  def removeThreadContext(pinstId: PinstId): Unit

  def addThreadContextDetail(pinstId: PinstId, threadContextDetail: ThreadContextDetail): Unit = {
    val updatedThreadContextDetails = state.currentThreadContexts ++ Map(pinstId -> threadContextDetail)
    addThreadContextDetail(ThreadContext(contexts = updatedThreadContextDetails))
  }

  //once we stop using agent-provisioning:0.5 and connecting:0.6 protocol
  //the below mentioned 'addPinst' will no longer be required.
  def addPinst(pri: ProtocolRunningInstances): Unit

  //once we stop using agent-provisioning:0.5 and connecting:0.6 protocol
  //the below mentioned 'addPinst' will no longer be required.
  def addPinst(protoRef: ProtoRef, pinstId: PinstId): Unit = {
    val curProtoInstances = state.protoInstances.map(_.instances).getOrElse(Map.empty)
    val updatedProtoInstances = curProtoInstances ++ Map(protoRef.toString -> pinstId)
    addPinst(ProtocolRunningInstances(instances = updatedProtoInstances))
  }

  //once we stop using agent-provisioning:0.5 and connecting:0.6 protocol
  //the below mentioned 'addPinst' will no longer be required.
  def addPinst(inst: (ProtoRef, PinstId)): Unit = addPinst(inst._1, inst._2)
}

/**
 * interface for agent's common state
 * agent common/base classes may/will use below members for reading/querying purposes.
 *
 */
trait AgentStateInterface extends State {

  def threadContext: Option[ThreadContext]

  def currentThreadContexts: Map[String, ThreadContextDetail] = threadContext.map(_.contexts).getOrElse(Map.empty)
  def currentThreadContextSize: Int = currentThreadContexts.size


  //once we stop using agent-provisioning:0.5 and connecting:0.6 protocol
  //the below mentioned 'addPinst' will no longer be required.
  def protoInstances: Option[ProtocolRunningInstances]

  def relationship: Option[Relationship]
  def relationshipReq: Relationship = relationship.getOrElse(throw new RuntimeException("relationship not found"))

  def agentWalletId: Option[String]
  def agentWalletIdReq: String = agentWalletId.getOrElse(
    throw new RuntimeException("agent wallet id not yet set")
  )
  def agencyDIDPair: Option[DidPair]
  def agencyDIDReq: DID = agencyDIDPair.map(_.DID).getOrElse(throw new RuntimeException("agency DID not available"))

  def domainId: DomainId
  def relationshipId: Option[RelationshipId] = relationship.flatMap(_.myDid)
  def contextualId: Option[String] = thisAgentKeyId

  /**
   * represents key id for the current/this agent
   * currently it is a DID associated with the agent's ver key
   *
   * @return
   */
  def thisAgentKeyId: Option[KeyId]

  def threadContextDetail(pinstId: PinstId): Option[ThreadContextDetail]
  def threadContextDetailReq(pinstId: PinstId): ThreadContextDetail
  def threadContextsContains(pinstId: PinstId): Boolean

  def getPinstId(protoDef: ProtoDef): Option[PinstId]

  def myDidDoc: Option[DidDoc] = relationship.flatMap(_.myDidDoc)
  def myDidDoc_! : DidDoc = myDidDoc.getOrElse(throw new RuntimeException("myDidDoc is not set yet"))
  def myDid: Option[DID] = myDidDoc.map(_.did)
  def myDid_! : DID = myDid.getOrElse(throw new RuntimeException("myDid is not set yet"))
  def myDidAuthKey: Option[AuthorizedKeyLike] = myDid.flatMap(keyId =>
    relationship.flatMap(_.myDidDocAuthKeyById(keyId)))
  def myDidAuthKeyReq: AuthorizedKeyLike = myDidAuthKey.getOrElse(
    throw new RuntimeException("my DID auth key is not set yet")
  )

  def theirDidDoc: Option[DidDoc] = relationship.flatMap(_.theirDidDoc)
  def theirDidDoc_! : DidDoc = theirDidDoc.getOrElse(throw new RuntimeException("theirDidDoc is not set yet"))
  def theirDid: Option[DID] = theirDidDoc.map(_.did)
  def theirDid_! : DID = theirDid.getOrElse(throw new RuntimeException("theirDid is not set yet"))
  def theirDidAuthKey: Option[AuthorizedKeyLike] = theirDid.flatMap(keyId =>
    relationship.flatMap(_.theirDidDocAuthKeyById(keyId)))
  def theirDidAuthKeyReq: AuthorizedKeyLike = theirDidAuthKey.getOrElse(
    throw new RuntimeException("their DID auth key is not set yet")
  )

  def thisAgentAuthKey: Option[AuthorizedKeyLike] = thisAgentKeyId.flatMap(keyId =>
    relationship.flatMap(_.myDidDocAuthKeyById(keyId)))

  def thisAgentAuthKeyDidPair: Option[DidPair] =
    thisAgentAuthKey
      .find(_.verKeyOpt.isDefined)
      .map(ak => DidPair(ak.keyId, ak.verKey))

  def thisAgentAuthKeyReq: AuthorizedKeyLike =
    thisAgentAuthKey.getOrElse(throw new RuntimeException("this agent auth key not found"))

  def thisAgentKeyDID: Option[KeyId] = thisAgentAuthKey.map(_.keyId)
  def thisAgentKeyDIDReq: DID = thisAgentKeyDID.getOrElse(throw new RuntimeException("this agent key id not found"))
  def thisAgentVerKey: Option[VerKey] = thisAgentAuthKey.filter(_.verKeyOpt.isDefined).map(_.verKey)
  def thisAgentVerKeyReq: VerKey = thisAgentVerKey.getOrElse(throw new RuntimeException("this agent ver key not found"))

  def ownerAgentVerKey: Option[VerKey] = relationship.flatMap(_.myDidDocAuthKeyByTag(OWNER_AGENT_KEY)).flatMap(_.verKeyOpt)

  def theirAgentAuthKey: Option[AuthorizedKeyLike] = relationship.flatMap(_.theirDidDocAuthKeyByTag(AGENT_KEY_TAG))
  def theirAgentAuthKeyReq: AuthorizedKeyLike = theirAgentAuthKey.getOrElse(
    throw new RuntimeException("their agent auth key not yet set"))
  def theirAgentKeyDID: Option[DID] = theirAgentAuthKey.map(_.keyId)
  def theirAgentKeyDIDReq: DID = theirAgentKeyDID.getOrElse(throw new RuntimeException("their agent auth key not yet set"))
  def theirAgentVerKey: Option[VerKey] = theirAgentAuthKey.flatMap(_.verKeyOpt)
  def theirAgentVerKeyReq: VerKey = theirAgentVerKey.getOrElse(throw new RuntimeException("their agent ver key not yet set"))

  def myAuthKeys: Set[AuthorizedKey] =
    relationship.flatMap(_.myDidDoc.flatMap(_.authorizedKeys.map(_.keys.toSet))).getOrElse(Set.empty)

  def myAuthVerKeys: Set[VerKey] = myAuthKeys.flatMap(k => k.verKeyOpt)

  def theirAuthVerKeys: Set[VerKey] =
    relationship.flatMap(_.theirDidDoc.flatMap(_.authorizedKeys.map(_.safeVerKeys))).getOrElse(Set.empty)
  def allAuthedVerKeys: Set[VerKey] = myAuthVerKeys ++ theirAuthVerKeys ++ theirAgentVerKey ++ ownerAgentVerKey

  def serializedSize: Int
}

trait AgentStatePairwiseInterface extends AgentStateInterface {
  def connectionStatus: Option[ConnectionStatus]
  def isConnectionStatusEqualTo(status: String): Boolean = connectionStatus.exists(_.answerStatusCode == status)
}