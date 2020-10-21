package com.evernym.verity.actor.agent.state.base

import com.evernym.verity.actor.agent.agency.SponsorRel
import com.evernym.verity.actor.agent.relationship.Relationship
import com.evernym.verity.actor.agent.state.RelationshipState
import com.evernym.verity.actor.agent.{ConnectionStatus, ThreadContext, ThreadContextDetail}
import com.evernym.verity.protocol.engine.{DID, PinstId, ProtoDef, ProtoRef}

//NOTE: over the period of time, as we make all agent actors starts using
//proto buf based state, this file should become obsolete and should be deleted

/**
 * this trait would be used to update agent's state
 * for proto buf state based self relationship actors (UserAgent)
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

/**
 * this trait would be used to update agent's state
 * for proto buf state based pairwise relationship actors (UserPairwiseAgent)
 */
trait LegacyAgentPairwiseStateUpdateImpl extends LegacyAgentStateUpdateImpl with AgentPairwiseStateUpdateInterface {
  type StateType <: LegacyAgentPairwiseStateImpl

  def setConnectionStatus(cs: ConnectionStatus): Unit =
    state.setConnectionStatus(cs)

  def setConnectionStatus(cso: Option[ConnectionStatus]): Unit =
    state.setConnectionStatus(cso)
}

/**
 * this trait would be used for proto buf state based self relationship actors (UserAgent)
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

/**
 * this trait would be used for proto buf state based pairwise relationship actors (UserAgentPairwise actor)
 */
trait LegacyAgentPairwiseStateImpl extends LegacyAgentStateImpl with AgentStatePairwiseInterface {
  private var _connectionStatus: Option[ConnectionStatus] = None
  def connectionStatus: Option[ConnectionStatus] = _connectionStatus
  def setConnectionStatus(cs: ConnectionStatus): Unit = _connectionStatus = Option(cs)
  def setConnectionStatus(cso: Option[ConnectionStatus]): Unit = _connectionStatus = cso
}
