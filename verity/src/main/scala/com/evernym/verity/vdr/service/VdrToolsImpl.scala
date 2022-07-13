package com.evernym.verity.vdr.service

import com.evernym.vdrtools.vdr.VDR
import com.evernym.vdrtools.vdr.VdrParams.CacheOptions
import com.evernym.vdrtools.vdr.VdrResults.{PingResult, PreparedTxnResult}
import com.evernym.verity.vdr._

import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters._
import scala.compat.java8.FutureConverters.{toScala => toFuture}
import scala.concurrent.Future

//A thin wrapper around VDRTools API for production code
class VdrToolsImpl(vdr: VDR) extends VdrTools {

  override def ping(namespaceList: List[Namespace]): Future[Map[Namespace, PingResult]] = {
    val fut: CompletableFuture[Map[Namespace, PingResult]] =
      vdr.ping(namespaceList.asJava).thenApply(x => x.asScala.toMap)
    toFuture(fut)
  }

  override def resolveDid(fqDid: FqDID): Future[VdrDid] = {
    toFuture(vdr.resolveDID(fqDid))
  }

  override def resolveDid(fqDid: FqDID,
                          cacheOptions: CacheOptions): Future[VdrDid] = {
    toFuture(vdr.resolveDID(fqDid))
  }

  override def resolveSchema(fqSchemaId: FqSchemaId): Future[VdrSchema] = {
    toFuture(vdr.resolveSchema(fqSchemaId))
  }

  override def resolveSchema(fqSchemaId: FqSchemaId,
                             cacheOptions: CacheOptions): Future[VdrSchema] = {
    toFuture(vdr.resolveSchema(fqSchemaId))
  }

  override def resolveCredDef(fqCredDefId: FqCredDefId): Future[VdrCredDef] = {
    toFuture(vdr.resolveCredDef(fqCredDefId))
  }

  override def resolveCredDef(fqCredDefId: FqCredDefId,
                              cacheOptions: CacheOptions): Future[VdrCredDef] = {
    toFuture(vdr.resolveCredDef(fqCredDefId))
  }

  override def prepareDid(txnSpecificParams: TxnSpecificParams,
                          submitterDid: FqDID,
                          endorser: Option[String]): Future[PreparedTxnResult] = {
    toFuture(vdr.prepareDID(txnSpecificParams, submitterDid, endorser.orNull))
  }

  override def prepareSchema(txnSpecificParams: TxnSpecificParams,
                             submitterDid: FqDID,
                             endorser: Option[String]): Future[PreparedTxnResult] = {
    toFuture(vdr.prepareSchema(txnSpecificParams, submitterDid, endorser.orNull))
  }

  override def prepareCredDef(txnSpecificParams: TxnSpecificParams,
                              submitterDid: FqDID,
                              endorser: Option[String]): Future[PreparedTxnResult] = {
    toFuture(vdr.prepareCredDef(txnSpecificParams, submitterDid, endorser.orNull))
  }

  override def submitTxn(namespace: Namespace,
                         txnBytes: Array[Byte],
                         signatureSpec: String,
                         signature: Array[Byte],
                         endorsement: String): Future[TxnResult] = {
    toFuture(vdr.submitTxn(namespace, txnBytes, signatureSpec, signature, endorsement))
  }

  override def submitRawTxn(namespace: Namespace,
                            txnBytes: Array[Byte]): Future[TxnResult] = {
    toFuture(vdr.submitRawTxn(namespace, txnBytes))
  }

  override def submitQuery(namespace: Namespace,
                           query: String): Future[TxnResult] = {
    toFuture(vdr.submitQuery(namespace, query))
  }
}