package com.evernym.verity.actor.agent.state

import com.evernym.verity.actor.agent.{ThreadContext, ThreadContextDetail}
import com.evernym.verity.protocol.engine.PinstId

/**
 * A trait meant to be mixed into the state object of an agent
 *
 * mapping between a 'pinstid' (protocol instance id) and its thread context detail
 * which is used during incoming and outgoing message handling (like packaging information etc)
 */
trait ThreadContexts {
  private var _threadContexts: ThreadContext = ThreadContext()

  def addThreadContextDetail(pinstId: PinstId, threadContextDetail: ThreadContextDetail): Unit = {
    _threadContexts = _threadContexts.copy(contexts = _threadContexts.contexts + (pinstId -> threadContextDetail))
  }
  def threadContextDetail(pinstId: PinstId): ThreadContextDetail = _threadContexts.contexts(pinstId)

  def threadContextsContains(pinstId: PinstId): Boolean = _threadContexts.contexts.contains(pinstId)
}

trait HasThreadContexts {
  type StateType <: ThreadContexts
  def state: StateType
}