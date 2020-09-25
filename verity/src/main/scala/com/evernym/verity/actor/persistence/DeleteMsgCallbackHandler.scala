package com.evernym.verity.actor.persistence

import akka.actor.Actor.Receive
import akka.persistence.{DeleteMessagesFailure, DeleteMessagesSuccess}

trait DeleteMsgCallbackHandler {

  def handleDeleteMsgSuccess(dms: DeleteMessagesSuccess): Unit

  def handleDeleteMsgFailure(dmf: DeleteMessagesFailure): Unit

  def deleteMsgCallbackCommandHandler: Receive = {
    case dms: DeleteMessagesSuccess  => handleDeleteMsgSuccess(dms)
    case dmf: DeleteMessagesFailure  => handleDeleteMsgFailure(dmf)
  }
}
