package com.evernym.verity.vdr.service

import com.evernym.vdrtools.vdr.VdrBuilder
import com.evernym.vdrtools.vdr.VdrParams.TaaConfig
import com.evernym.verity.config.AppConfig

import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters._
import scala.compat.java8.FutureConverters.{toScala => toFuture}
import scala.concurrent.{ExecutionContext, Future}

class VdrToolsBuilderImpl(appConfig: AppConfig)(implicit ec: ExecutionContext) extends VdrToolsBuilder {

  private val builder = VdrBuilder.create()

  override def registerIndyLedger(namespaceList: List[String],
                                  genesisTxnData: String,
                                  taaConfig: Option[TaaConfig]): Future[Unit] = {
    val fut: CompletableFuture[Unit] = builder.registerIndyLedger(namespaceList.asJava, genesisTxnData, taaConfig.orNull).thenApply(_ => ())
    toFuture(fut)
  }

  override def registerCheqdLedger(namespaceList: List[String],
                                   chainId: String,
                                   nodeAddrsList: String): Future[Unit] = {
    val fut: CompletableFuture[Unit] = builder.registerCheqdLedger(namespaceList.asJava, chainId, nodeAddrsList).thenApply(_ => ())
    toFuture(fut)
  }

  override def build(): VdrTools = {
    val vdr = builder.build()
    new VdrToolsImpl(vdr)
  }
}
