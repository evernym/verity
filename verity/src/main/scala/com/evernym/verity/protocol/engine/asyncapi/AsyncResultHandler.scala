package com.evernym.verity.protocol.engine.asyncapi

import scala.util.Try

trait AsyncResultHandler {
  def handleResult[T](result: Try[Any], handler: Try[T] => Unit): Unit
}
