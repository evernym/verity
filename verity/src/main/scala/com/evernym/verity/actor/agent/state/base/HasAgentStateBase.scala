package com.evernym.verity.actor.agent.state.base

import com.evernym.verity.actor.agent.relationship.Relationship
import com.evernym.verity.actor.agent.{ProtocolRunningInstances, ThreadContext, ThreadContextDetail}
import com.evernym.verity.protocol.engine._

//NOTE: over the period of time, as we make all agent actors starts using
//proto buf based state, this class should drive the common base implementation
//of interface mentioned in 'AgentStateBaseInterface'

/**
 * this trait would be used for proto buf state based self relationship actors (AgencyAgent)
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

/**
 * this trait would be used for proto buf state based pairwise relationship actors (AgencyAgentPairwise actor)
 */
trait AgentStatePairwiseImplBase extends AgentStatePairwiseInterface with AgentStateImplBase