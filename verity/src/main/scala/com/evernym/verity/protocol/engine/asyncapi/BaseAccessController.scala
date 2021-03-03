package com.evernym.verity.protocol.engine.asyncapi

import scala.util.{Failure, Try}

trait BaseAccessController {

  def asyncOpRunner: AsyncOpRunner
  def accessRights: Set[AccessRight]

  def withAsyncOpRunner[T](asyncOp: => Any,
                           cbHandler: Try[T] => Unit): Unit = {
    asyncOpRunner.withAsyncOpRunner(asyncOp, cbHandler)
  }

  def runIfAllowed[T](right: AccessRight, f: (Try[T] => Unit) => Unit, handler: Try[T] => Unit): Unit =
    if(accessRights(right)) {
      //TODO: the handler which is supplied to function 'f' in below line is not used
      // may be we can refactor this whole async api interface to avoid passing it
      withAsyncOpRunner(f(handler), handler)
    } else {
      handler(Failure(new IllegalAccessException))
    }
}
