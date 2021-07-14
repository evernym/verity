package com.evernym.verity.actor.agent.msghandler

import com.evernym.verity.util2.Exceptions.InternalServerErrorException
import com.evernym.verity.util2.Status.UNHANDLED

trait MsgParam {

  def givenMsg: Any

  def supportedTypes: List[Class[_]]

  def validate(): Unit = {
    if (! supportedTypes.contains(givenMsg.getClass)) {
      throw new InternalServerErrorException(UNHANDLED.statusCode, Option(s"message type not supported: $givenMsg"))
    }
  }

  validate()
}
