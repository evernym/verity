package com.evernym.verity.actor.agent.state.base

import com.evernym.verity.actor.State
import com.evernym.verity.actor.agent.relationship.Tags.AGENT_KEY_TAG
import com.evernym.verity.actor.agent.relationship.{AuthorizedKeyLike, DidDoc, KeyId, Relationship}
import com.evernym.verity.actor.agent.{ConnectionStatus, ProtocolRunningInstances, ThreadContext, ThreadContextDetail}
import com.evernym.verity.protocol.engine._

/**
 * interface for agent's common state update (for self relationship actors)
 * agent common/base classes will call below function to update agent's state
 */
trait AgentStateUpdateInterface {
  type StateType <: AgentStateInterface
  def state: StateType

  def setAgentWalletSeed(seed: String): Unit
  def setAgencyDID(did: DID): Unit
  def addThreadContextDetail(threadContext: ThreadContext): Unit
  def removeThreadContext(pinstId: PinstId): Unit
  def addThreadContextDetail(pinstId: PinstId, threadContextDetail: ThreadContextDetail): Unit = {
    val curThreadContextDetails = state.threadContext.map(_.contexts).getOrElse(Map.empty)
    val updatedThreadContextDetails = curThreadContextDetails ++ Map(pinstId -> threadContextDetail)
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

  //once we stop using agent-provisioning:0.5 and connecting:0.6 protocol
  //the below mentioned 'addPinst' will no longer be required.
  def protoInstances: Option[ProtocolRunningInstances]

  def relationship: Option[Relationship]
  def relationshipReq: Relationship = relationship.getOrElse(throw new RuntimeException("relationship not found"))

  def agentWalletSeed: Option[String]
  def agencyDID: Option[DID]
  def agencyDIDReq: DID = agencyDID.getOrElse(throw new RuntimeException("agency DID not available"))

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

  def theirDidDoc: Option[DidDoc] = relationship.flatMap(_.theirDidDoc)
  def theirDidDoc_! : DidDoc = theirDidDoc.getOrElse(throw new RuntimeException("theirDidDoc is not set yet"))
  def theirDid: Option[DID] = theirDidDoc.map(_.did)
  def theirDid_! : DID = theirDid.getOrElse(throw new RuntimeException("theirDid is not set yet"))

  def thisAgentAuthKey: Option[AuthorizedKeyLike] = thisAgentKeyId.flatMap(keyId =>
    relationship.flatMap(_.myDidDocAuthKeyById(keyId)))
  def thisAgentKeyDID: Option[KeyId] = thisAgentAuthKey.map(_.keyId)
  def thisAgentKeyDIDReq: DID = thisAgentKeyDID.getOrElse(throw new RuntimeException("this agent key id not found"))
  def thisAgentVerKey: Option[VerKey] = thisAgentAuthKey.filter(_.verKeyOpt.isDefined).map(_.verKey)
  def thisAgentVerKeyReq: VerKey = thisAgentVerKey.getOrElse(throw new RuntimeException("this agent ver key not found"))

  def theirAgentAuthKey: Option[AuthorizedKeyLike] = relationship.flatMap(_.theirDidDocAuthKeyByTag(AGENT_KEY_TAG))
  def theirAgentAuthKeyReq: AuthorizedKeyLike = theirAgentAuthKey.getOrElse(
    throw new RuntimeException("their agent auth key not yet set"))
  def theirAgentKeyDID: Option[DID] = theirAgentAuthKey.map(_.keyId)
  def theirAgentKeyDIDReq: DID = theirAgentKeyDID.getOrElse(throw new RuntimeException("their agent auth key not yet set"))
  def theirAgentVerKey: Option[VerKey] = theirAgentAuthKey.flatMap(_.verKeyOpt)
  def theirAgentVerKeyReq: VerKey = theirAgentVerKey.getOrElse(throw new RuntimeException("their agent ver key not yet set"))

  def myAuthVerKeys: Set[VerKey] =
    relationship.flatMap(_.myDidDoc.flatMap(_.authorizedKeys.map(_.safeVerKeys))).getOrElse(Set.empty)
  def theirAuthVerKeys: Set[VerKey] =
    relationship.flatMap(_.theirDidDoc.flatMap(_.authorizedKeys.map(_.safeVerKeys))).getOrElse(Set.empty)
  def allAuthedVerKeys: Set[VerKey] = myAuthVerKeys ++ theirAuthVerKeys

  def serializedSize: Int
}

trait AgentStatePairwiseInterface extends AgentStateInterface {
  def connectionStatus: Option[ConnectionStatus]
  def isConnectionStatusEqualTo(status: String): Boolean = connectionStatus.exists(_.answerStatusCode == status)
}