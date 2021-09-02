package com.evernym.verity.actor.agent.state.base

import com.evernym.verity.actor.agent.relationship.{EndpointADT, EndpointADTUntyped, EndpointId, KeyId, Relationship, Tags}
import com.evernym.verity.actor.agent.{ProtocolRunningInstances, ThreadContext, ThreadContextDetail}
import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.protocol.engine._

//NOTE: over the period of time, as we make all agent actors starts using
//proto buf based state, this class should drive the common base implementation
//of interface mentioned in 'AgentStateBaseInterface'

/**
 * this trait would be used for proto buf state based self relationship actors (AgencyAgent)
 */
trait AgentStateImplBase extends AgentStateInterface {

  def threadContext: Option[ThreadContext]
  def threadContextReq: ThreadContext = threadContext.getOrElse(
    throw new RuntimeException("thread context not available"))

  def threadContextDetail(pinstId: PinstId): Option[ThreadContextDetail] =
    threadContext.flatMap(_.contexts.get(pinstId))
  def threadContextDetailReq(pinstId: PinstId): ThreadContextDetail =
    threadContextReq.contexts(pinstId)

  def threadContextsContains(pinstId: PinstId): Boolean =
    threadContext.exists(_.contexts.contains(pinstId))

  def protoInstances: Option[ProtocolRunningInstances]
  def getPinstId(protoDef: ProtoDef): Option[PinstId] =
    protoInstances.flatMap(_.instances.get(protoDef.protoRef.toString))

  def relWithEndpointRemoved(endpointId: EndpointId): Option[Relationship] = {
    relationship.map(_.copy(myDidDoc = relationship.flatMap(_.myDidDoc.map(_.updatedWithRemovedEndpointById(endpointId)))))
  }

  def relWithAuthKeyMergedToMyDidDoc(keyId: KeyId, verKey: VerKeyStr, tags: Set[Tags]): Option[Relationship] = {
    relationship.map(_.copy(myDidDoc = relationship.flatMap(_.myDidDoc.map(_.updatedWithMergedAuthKey(keyId, verKey, tags)))))
  }

  def relWithEndpointAddedOrUpdatedInMyDidDoc(endpoint: EndpointADTUntyped): Option[Relationship] = {
    relationship.map(_.copy(myDidDoc = relationship.flatMap(_.myDidDoc.map(
      _.updatedWithEndpoint(EndpointADT(endpoint))))))
  }

  def relWithNewAuthKeyAddedInMyDidDoc(keyId: KeyId, verKey: VerKeyStr, tags: Set[Tags]): Option[Relationship] = {
    relationship.map(_.copy(myDidDoc = relationship.flatMap(_.myDidDoc.map(_.updatedWithNewAuthKey(keyId, verKey, tags)))))
  }

  def relWithNewAuthKeyAddedInMyDidDoc(keyId: KeyId, tags: Set[Tags]): Option[Relationship] = {
    relationship.map(_.copy(myDidDoc = relationship.flatMap(_.myDidDoc.map(_.updatedWithNewAuthKey(keyId, tags)))))
  }
}

/**
 * this trait would be used for proto buf state based pairwise relationship actors (AgencyAgentPairwise actor)
 */
trait AgentStatePairwiseImplBase extends AgentStatePairwiseInterface with AgentStateImplBase