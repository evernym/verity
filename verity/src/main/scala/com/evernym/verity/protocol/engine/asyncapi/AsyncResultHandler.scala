package com.evernym.verity.protocol.engine.asyncapi

import scala.util.Try

trait AsyncResultHandler {

  def handleAsyncOpResult[T](handler: Try[T] => Unit): Try[T] => Unit = {
    {t: Try[_] => handleResult(t, handler)}
  }

  def handleResult[T](result: Try[Any], handler: Try[T] => Unit): Unit
}
