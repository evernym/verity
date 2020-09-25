package com.evernym.verity.actor.agent

import com.evernym.verity.Exceptions.{EmptyValueForOptionalFieldException, MissingReqFieldException, NotFoundErrorException}
import com.evernym.verity.Status.UNSUPPORTED_MSG_TYPE
import com.evernym.verity.protocol.engine.util.{?=>, getErrorMsg}
import com.evernym.verity.protocol.engine.{EmptyValueForOptionalFieldProtocolEngineException, MissingReqFieldProtocolEngineException, UnsupportedMessageFamily, UnsupportedMessageType}

trait ProtocolEngineExceptionHandler {

  protected def protoExceptionHandler: Throwable ?=> Nothing = convertProtoEngineException andThen { throw _ }

  //TODO: may need a better name for the function
  //NOTE: not sure if this needs to be a partial function only (the goal may be achieved by different ways)
  //basically, we want a function which takes an exception thrown by protocol engine
  //and converts it to the exception which we want to throw to the layers above
  protected def convertProtoEngineException: Throwable ?=> Throwable = {
    case e: UnsupportedMessageType =>
      new NotFoundErrorException(UNSUPPORTED_MSG_TYPE.statusCode, Option(getErrorMsg(e)))
    case e: UnsupportedMessageFamily =>
      new NotFoundErrorException(UNSUPPORTED_MSG_TYPE.statusCode, Option(getErrorMsg(e)))
    case e: EmptyValueForOptionalFieldProtocolEngineException =>
      new EmptyValueForOptionalFieldException(Option(getErrorMsg(e)))
    case e: MissingReqFieldProtocolEngineException =>
      new MissingReqFieldException(Option(getErrorMsg(e)))
    case e: Exception => e //TODO should probably wrap this in a generic protocol engine exception
  }

}
