package com.evernym.verity.protocol.engine.asyncapi

import scala.concurrent.Future
import scala.util.{Failure, Try}

trait BaseAccessController {

  def asyncOpRunner: AsyncOpRunner

  def withAsyncOpRunner[T](asyncOp: => Any,
                           cbHandler: Try[T] => Unit): Unit = {
    asyncOpRunner.withAsyncOpRunner(asyncOp, cbHandler)
  }

  def withFutureOpRunner[T](fut: Future[Any],
                            cbHandler: Try[T] => Unit): Unit = {
    asyncOpRunner.withFutureOpRunner(fut, cbHandler)
  }
}
