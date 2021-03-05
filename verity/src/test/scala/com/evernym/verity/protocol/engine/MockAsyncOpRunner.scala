package com.evernym.verity.protocol.engine

import com.evernym.verity.protocol.engine.asyncapi.AsyncOpRunner

trait MockAsyncOpRunner
  extends AsyncOpRunner {

  implicit val asyncOpRunner: AsyncOpRunner = this
  override protected def runAsyncOp(op: => Any): Unit = op

  def postAllAsyncOpsCompleted(): Unit = ???
  def abortTransaction(): Unit = ???
}
