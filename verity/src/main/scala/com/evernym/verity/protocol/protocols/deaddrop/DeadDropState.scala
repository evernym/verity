package com.evernym.verity.protocol.protocols.deaddrop

sealed trait DeadDropState

object DeadDropState {

  case class Uninitialized() extends DeadDropState
  case class Initialized() extends DeadDropState
  case class StoreInProgress() extends DeadDropState
  case class Ready() extends DeadDropState
  case class Done() extends DeadDropState

  case class ItemRetrieved(ddp: Option[DeadDropPayload]) extends DeadDropState

}
