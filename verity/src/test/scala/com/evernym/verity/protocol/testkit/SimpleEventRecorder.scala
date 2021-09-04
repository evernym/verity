package com.evernym.verity.protocol.testkit

import com.evernym.verity.protocol.engine.PinstId
import com.evernym.verity.protocol.engine.container.RecordsEvents

class SimpleEventRecorder(initialState: Any) extends RecordsEvents {

  var state: Any = initialState
  var events: Vector[_] = Vector()

  override def recoverState(pinstId: PinstId): (_, Vector[_]) = (state, events)

  // Implementers of RecordsEvents have two choices here. They can keep track of state or they can keep track of events.
  // If they decide to keep track of state, then the only events that should be returned in recoverState should be those to be applied after the received state.
  // In this case we are always returning the initial state and only keeping track of events.
  override def record(pinstId: PinstId, event: Any, state: Any)(cb: Any => Unit): Unit = {
    events = events :+ event
    cb(event)
  }
}
