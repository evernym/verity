package com.evernym.verity.protocol.engine

import com.evernym.verity.protocol.engine.asyncService.AsyncOpRunner

import scala.util.Try

package object asyncService {
  type AsyncOpCallbackHandler[T] = Try[T] => Unit
}

trait BaseAsyncAccessImpl {
  def asyncOpRunner: AsyncOpRunner

  def withAsyncOpRunner[T](asyncOp: => Any, cbHandler: Try[T] => Unit): Unit =
    asyncOpRunner.withAsyncOpRunner(asyncOp, cbHandler)
}