package com.evernym.verity.libindy.wallet.api

import java.util.concurrent.CompletableFuture

import scala.compat.java8.FutureConverters
import scala.concurrent.Future

trait FutureConverter {

  def asScalaFuture[T](codeBlock: => CompletableFuture[T]): Future[T] = {
    FutureConverters.toScala(codeBlock)
  }
}
