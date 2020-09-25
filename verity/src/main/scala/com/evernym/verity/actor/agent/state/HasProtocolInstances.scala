package com.evernym.verity.actor.agent.state

import com.evernym.verity.protocol.engine.{PinstId, ProtoDef, ProtoRef}

/**
 * A trait meant to be mixed into the state object of an agent
 *
 * mapping between protocol ref and pinst id (entity id of the actor protocol container)
 * so far these are only those situation where protocol instance gets created/launched in one context (say UserAgent)
 * but then it continues its rest interaction from a different context (say UserAgentPairwise)
 * and we wanted to make sure it always launches the "same" protocol instance
 */
trait ProtocolInstances {

  private var _instances: Map[ProtoRef, PinstId] = Map.empty
  def addPinst(protoRef: ProtoRef, pinstId: PinstId): Unit = addPinst(protoRef -> pinstId)
  def addPinst(inst: (ProtoRef, PinstId)): Unit = _instances = _instances + inst
  def getPinstId(protoDef: ProtoDef): Option[PinstId] = _instances.get(protoDef.msgFamily.protoRef)
}

trait HasProtocolInstances {
  type StateType <: ProtocolInstances
  def state: StateType
}
