package com.evernym.verity.actor.agent.state

import com.evernym.verity.actor.State
import com.evernym.verity.actor.agent.agency.SponsorRel
import com.evernym.verity.actor.agent.relationship.Tags.AGENT_KEY_TAG
import com.evernym.verity.actor.agent.{ConnectionStatus, ProtocolRunningInstances, ThreadContext, ThreadContextDetail}
import com.evernym.verity.actor.agent.relationship.{AuthorizedKeyLike, DidDoc, KeyId, Relationship}
import com.evernym.verity.protocol.engine.{DID, PinstId, ProtoDef, ProtoRef, VerKey}

/**
 * interface for agent's common state update
 * agent common/base classes will call below function to update agent's state
 */
trait AgentStateUpdateInterface {
  def setAgentWalletSeed(seed: String): Unit
  def setAgencyDID(did: DID): Unit
  def setSponsorRel(rel: SponsorRel): Unit
  def addThreadContextDetail(pinstId: PinstId, threadContextDetail: ThreadContextDetail): Unit
  def addPinst(protoRef: ProtoRef, pinstId: PinstId): Unit
  def addPinst(inst: (ProtoRef, PinstId)): Unit
}

trait AgentPairwiseStateUpdateInterface extends AgentStateUpdateInterface {
  def setConnectionStatus(cs: ConnectionStatus): Unit
  def setConnectionStatus(cso: Option[ConnectionStatus]): Unit
  def updateRelationship(rel: Relationship): Unit
}

/**
 * interface for agent's common state
 * agent common/base classes may/will use below members for reading/querying purposes.
 *
 */
trait AgentStateInterface extends State {

  def relationshipOpt: Option[Relationship]
  def relationshipReq: Relationship = relationshipOpt.getOrElse(throw new RuntimeException("relationship not found"))

  def sponsorRel: Option[SponsorRel] = None
  def agentWalletSeed: Option[String]
  def agencyDID: Option[DID]
  def agencyDIDReq: DID = agencyDID.getOrElse(throw new RuntimeException("agency DID not available"))

  def thisAgentKeyId: Option[KeyId]

  def threadContextDetail(pinstId: PinstId): ThreadContextDetail
  def threadContextsContains(pinstId: PinstId): Boolean

  def getPinstId(protoDef: ProtoDef): Option[PinstId]

  def myDidDoc: Option[DidDoc] = relationshipOpt.flatMap(_.myDidDoc)
  def myDidDoc_! : DidDoc = myDidDoc.getOrElse(throw new RuntimeException("myDidDoc is not set yet"))
  def myDid: Option[DID] = myDidDoc.map(_.did)
  def myDid_! : DID = myDid.getOrElse(throw new RuntimeException("myDid is not set yet"))

  def theirDidDoc: Option[DidDoc] = relationshipOpt.flatMap(_.theirDidDoc)
  def theirDidDoc_! : DidDoc = theirDidDoc.getOrElse(throw new RuntimeException("theirDidDoc is not set yet"))
  def theirDid: Option[DID] = theirDidDoc.map(_.did)
  def theirDid_! : DID = theirDid.getOrElse(throw new RuntimeException("theirDid is not set yet"))

  def thisAgentAuthKey: Option[AuthorizedKeyLike] = thisAgentKeyId.flatMap(keyId =>
    relationshipOpt.flatMap(_.myDidDocAuthKeyById(keyId)))
  def thisAgentKeyDID: Option[KeyId] = thisAgentAuthKey.map(_.keyId)
  def thisAgentKeyDIDReq: DID = thisAgentKeyDID.getOrElse(throw new RuntimeException("this agent key id not found"))
  def thisAgentVerKey: Option[VerKey] = thisAgentAuthKey.filter(_.verKeyOpt.isDefined).map(_.verKey)
  def thisAgentVerKeyReq: VerKey = thisAgentVerKey.getOrElse(throw new RuntimeException("this agent ver key not found"))

  def theirAgentAuthKey: Option[AuthorizedKeyLike] = relationshipOpt.flatMap(_.theirDidDocAuthKeyByTag(AGENT_KEY_TAG))
  def theirAgentAuthKeyReq: AuthorizedKeyLike = theirAgentAuthKey.getOrElse(
    throw new RuntimeException("their agent auth key not yet set"))
  def theirAgentKeyDID: Option[DID] = theirAgentAuthKey.map(_.keyId)
  def theirAgentKeyDIDReq: DID = theirAgentKeyDID.getOrElse(throw new RuntimeException("their agent auth key not yet set"))
  def theirAgentVerKey: Option[VerKey] = theirAgentAuthKey.flatMap(_.verKeyOpt)
  def theirAgentVerKeyReq: VerKey = theirAgentVerKey.getOrElse(throw new RuntimeException("their agent ver key not yet set"))

  def myAuthVerKeys: Set[VerKey] =
    relationshipOpt.flatMap(_.myDidDoc.flatMap(_.authorizedKeys.map(_.safeVerKeys))).getOrElse(Set.empty)
  def theirAuthVerKeys: Set[VerKey] =
    relationshipOpt.flatMap(_.theirDidDoc.flatMap(_.authorizedKeys.map(_.safeVerKeys))).getOrElse(Set.empty)
  def allAuthedVerKeys: Set[VerKey] = myAuthVerKeys ++ theirAuthVerKeys

  def serializedSize: Int
}

trait AgentStatePairwiseInterface extends AgentStateInterface {
  def connectionStatus: Option[ConnectionStatus]
  def isConnectionStatusEqualTo(status: String): Boolean = connectionStatus.exists(_.answerStatusCode == status)
}

/**
 * this trait would be used for "NON proto buf state" actors (UserAgent and UserAgentPairwise actor)
 */
trait LegacyAgentStateUpdateImpl extends AgentStateUpdateInterface {

  type StateType <: LegacyAgentStateImpl
  def state: StateType

  override def setAgentWalletSeed(seed: String): Unit =
    state.setAgentWalletSeed(seed)

  override def setAgencyDID(did: DID): Unit =
    state.setAgencyDID(did)

  override def setSponsorRel(rel: SponsorRel): Unit =
    state.setSponsorRel(rel)

  override def addThreadContextDetail(pinstId: PinstId, threadContextDetail: ThreadContextDetail): Unit =
    state.addThreadContextDetail(pinstId, threadContextDetail)

  override def addPinst(protoRef: ProtoRef, pinstId: PinstId): Unit =
    state.addPinst(protoRef, pinstId)

  override def addPinst(inst: (ProtoRef, PinstId)): Unit =
    state.addPinst(inst)
}

trait LegacyAgentPairwiseStateUpdateImpl extends LegacyAgentStateUpdateImpl with AgentPairwiseStateUpdateInterface {
  type StateType <: LegacyAgentPairwiseStateImpl

  def setConnectionStatus(cs: ConnectionStatus): Unit =
    state.setConnectionStatus(cs)

  def setConnectionStatus(cso: Option[ConnectionStatus]): Unit =
    state.setConnectionStatus(cso)
}
/**
 * implementation of 'AgentStateInterface' inherited by for "NON proto buf state" actors
 * (UserAgent and UserAgentPairwise actor)
 */
trait LegacyAgentStateImpl extends AgentStateInterface with RelationshipState {

  override def relationshipOpt: Option[Relationship] = Option(relationship)

  private var _agentWalletSeed: Option[String] = None
  def agentWalletSeed: Option[String] = _agentWalletSeed
  def setAgentWalletSeed(seed: String): Unit = _agentWalletSeed = Option(seed)

  private var _agencyDID: Option[DID] = None
  def agencyDID: Option[DID] = _agencyDID
  def setAgencyDID(did: DID): Unit = _agencyDID = Option(did)

  private var _sponsorRel: Option[SponsorRel] = None
  override def sponsorRel: Option[SponsorRel] = _sponsorRel
  def setSponsorRel(rel: SponsorRel): Unit = {
    _sponsorRel = _sponsorRel match {
      case None => Some(rel)
      case Some(x) => throw new UnsupportedOperationException(s"sponsor relationship can't be overridden: $x")
    }
  }

  private var _threadContexts: ThreadContext = ThreadContext()
  def addThreadContextDetail(pinstId: PinstId, threadContextDetail: ThreadContextDetail): Unit = {
    _threadContexts = _threadContexts.copy(contexts = _threadContexts.contexts + (pinstId -> threadContextDetail))
  }
  override def threadContextDetail(pinstId: PinstId): ThreadContextDetail = _threadContexts.contexts(pinstId)
  override def threadContextsContains(pinstId: PinstId): Boolean = _threadContexts.contexts.contains(pinstId)

  private var _instances: Map[ProtoRef, PinstId] = Map.empty

  def addPinst(protoRef: ProtoRef, pinstId: PinstId): Unit = addPinst(protoRef -> pinstId)
  def addPinst(inst: (ProtoRef, PinstId)): Unit = _instances = _instances + inst
  override def getPinstId(protoDef: ProtoDef): Option[PinstId] = _instances.get(protoDef.msgFamily.protoRef)

}

trait LegacyAgentPairwiseStateImpl extends LegacyAgentStateImpl with AgentStatePairwiseInterface {
  private var _connectionStatus: Option[ConnectionStatus] = None
  def connectionStatus: Option[ConnectionStatus] = _connectionStatus
  def setConnectionStatus(cs: ConnectionStatus): Unit = _connectionStatus = Option(cs)
  def setConnectionStatus(cso: Option[ConnectionStatus]): Unit = _connectionStatus = cso
}

/**
 * this trait would be used for proto buf state based actors
 */
trait AgentStateImplBase extends AgentStateInterface {

  def relationship: Option[Relationship]
  def relationshipOpt: Option[Relationship] = relationship

  def threadContext: Option[ThreadContext]
  def threadContextReq: ThreadContext = threadContext.getOrElse(
    throw new RuntimeException("thread context not available"))
  def threadContextDetail(pinstId: PinstId): ThreadContextDetail =
    threadContextReq.contexts(pinstId)
  def threadContextsContains(pinstId: PinstId): Boolean =
    threadContext.exists(_.contexts.contains(pinstId))

  def protoInstances: Option[ProtocolRunningInstances]
  def getPinstId(protoDef: ProtoDef): Option[PinstId] =
    protoInstances.flatMap(_.instances.get(protoDef.msgFamily.protoRef.toString))

}

trait AgentStatePairwiseImplBase extends AgentStatePairwiseInterface with AgentStateImplBase