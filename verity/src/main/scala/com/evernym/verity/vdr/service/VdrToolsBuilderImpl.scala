package com.evernym.verity.vdr.service

import com.evernym.vdrtools.vdr.VdrBuilder

import scala.compat.java8.FutureConverters.{toScala => toFuture}
import scala.concurrent.Future

class VdrToolsBuilderImpl extends VdrToolsBuilder {

  private val builder = VdrBuilder.create()

  override def registerIndyLedger(namespaceList: List[String],
                                  genesisTxnData: String,
                                  taaConfig: Option[TaaConfig]): Future[Unit] = {
    // todo temporary code until vdr version is bumped
    toFuture(builder.registerIndyLedger(namespaceList.mkString("[\"", "\",\"" ,"\"]"), genesisTxnData, null).thenApply(_ => Unit))
  }

  override def registerCheqdLedger(namespaceList: List[String],
                                   chainId: String,
                                   nodeAddrsList: String): Future[Unit] = {
    // todo temporary code until vdr version is bumped
    toFuture(builder.registerCheqdLedger(namespaceList.mkString("[\"", "\",\"" ,"\"]"), chainId, nodeAddrsList).thenApply(_ => Unit))
  }

  override def build(): VdrTools = {
    val vdr = builder.build()
    new VdrToolsImpl(vdr)
  }
}
