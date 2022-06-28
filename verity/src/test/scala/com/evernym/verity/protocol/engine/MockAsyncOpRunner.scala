package com.evernym.verity.protocol.engine

import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByName
import com.evernym.verity.protocol.engine.asyncapi.AsyncOpRunner
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}

trait MockAsyncOpRunner
  extends AsyncOpRunner {

  implicit val asyncOpRunner: AsyncOpRunner = this

  implicit val ec: ExecutionContext = mockExecutionContext

  override protected def runAsyncOp(op: => Any): Unit = op

  override protected def runFutureAsyncOp(op: => Future[Any]): Unit = op.onComplete{r => executeCallbackHandler(r)}

  def postAllAsyncOpsCompleted(): Unit = ???
  def abortTransaction(): Unit = ???

  def mockExecutionContext: ExecutionContext

  override def logger: Logger = getLoggerByName(getClass.getSimpleName)
}
