package com.evernym.verity.protocol.engine

import com.evernym.verity.protocol.engine.asyncapi.AsyncOpRunner
import com.evernym.verity.testkit.BasicSpec

trait BaseAccessControllerSpec
  extends BasicSpec
  with AsyncOpRunner {

  implicit def asyncOpRunner: AsyncOpRunner = this
  override def postAllAsyncOpsCompleted(): Unit = ???
  override def abortTransaction(): Unit = ???
  override protected def runAsyncOp(op: => Any): Unit = ???
}
