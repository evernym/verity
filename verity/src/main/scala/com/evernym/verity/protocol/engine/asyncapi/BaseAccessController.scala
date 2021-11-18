package com.evernym.verity.protocol.engine.asyncapi

import scala.concurrent.Future
import scala.util.{Failure, Try}

trait BaseAccessController {

  def asyncOpRunner: AsyncOpRunner
  def accessRights: Set[AccessRight]

  def withAsyncOpRunner[T](asyncOp: => Any,
                           cbHandler: Try[T] => Unit): Unit = {
    asyncOpRunner.withAsyncOpRunner(asyncOp, cbHandler)
  }

  def withAsyncFutureOpRunner[T](asyncOp: => Future[Any],
                                 cbHandler: Try[T] => Unit): Unit = {
    asyncOpRunner.withAsyncFutureOpRunner(asyncOp, cbHandler)
  }

  def runIfAllowed[T](right: AccessRight, f: => Unit, handler: Try[T] => Unit): Unit =
    if(accessRights(right)) {
      withAsyncOpRunner(f, handler)
    } else {
      handler(Failure(new IllegalAccessException))
    }
}
