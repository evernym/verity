package com.evernym.verity.protocol.engine

/**
  * Services that records state transitioning events
  *
  */
trait RecordsEvents {

  def recoverState(pinstId: PinstId): (_, Vector[_]) // Returns State and Vector[Events]. TODO: Make types more clear here.

  def record(pinstId: PinstId, event: Any, state: Any, cb: Any => Unit): Unit

}
