package com.evernym.verity.protocol.engine

import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByName
import com.evernym.verity.protocol.engine.asyncapi.AsyncOpRunner
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

trait MockAsyncOpRunner
  extends AsyncOpRunner {

  implicit val asyncOpRunner: AsyncOpRunner = this
  override protected def runAsyncOp(op: => Any): Unit = op

  override protected def runFutureAsyncOp(op: => Future[Any]): Unit = op

  def postAllAsyncOpsCompleted(): Unit = ???
  def abortTransaction(): Unit = ???

  override def logger: Logger = getLoggerByName(getClass.getSimpleName)
}
