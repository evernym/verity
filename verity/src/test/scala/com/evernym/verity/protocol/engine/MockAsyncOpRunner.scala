package com.evernym.verity.protocol.engine

import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.evernym.verity.protocol.engine.asyncapi.AsyncOpRunner
import com.typesafe.scalalogging.Logger

trait MockAsyncOpRunner
  extends AsyncOpRunner {

  implicit val asyncOpRunner: AsyncOpRunner = this
  override protected def runAsyncOp(op: => Any): Unit = op

  def postAllAsyncOpsCompleted(): Unit = ???
  def abortTransaction(): Unit = ???

  override def logger: Logger = getLoggerByName(getClass.getSimpleName)
}
