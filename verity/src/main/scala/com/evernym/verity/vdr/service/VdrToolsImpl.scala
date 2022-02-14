package com.evernym.verity.vdr.service

import com.evernym.vdrtools.vdr.VdrParams.CacheOptions
import com.evernym.vdrtools.vdr.VdrResults.PingResult
import com.evernym.vdrtools.vdr.{VDR, VdrResults}
import com.evernym.verity.did.DidStr
import com.evernym.verity.vdr._

import java.util.concurrent.CompletableFuture
import scala.collection.JavaConverters._
import scala.compat.java8.FutureConverters.{toScala => toFuture}
import scala.concurrent.Future

//A thin wrapper around VDRTools API for production code
class VdrToolsImpl(val vdr: VDR) extends VdrTools {

  override def ping(namespaceList: List[Namespace]): Future[Map[String, PingResult]] = {
    val fut: CompletableFuture[Map[String, PingResult]] = vdr.ping(namespaceList.asJava).thenApply(x => x.asScala.toMap)
    toFuture(fut)
  }

  override def resolveDid(fqDid: FQDid): Future[VdrDid] = {
    toFuture(vdr.resolveDID(fqDid))
  }

  override def resolveDid(fqDid: FQDid,
                          cacheOptions: CacheOptions): Future[VdrDid] = {
    toFuture(vdr.resolveDID(fqDid, cacheOptions))
  }

  override def resolveSchema(fqSchemaId: FQSchemaId): Future[VdrSchema] = {
    toFuture(vdr.resolveSchema(fqSchemaId))
  }

  override def resolveSchema(fqSchemaId: FQSchemaId,
                             cacheOptions: CacheOptions): Future[VdrSchema] = {
    toFuture(vdr.resolveSchema(fqSchemaId, cacheOptions))
  }

  override def resolveCredDef(fqCredDefId: FQCredDefId): Future[VdrCredDef] = {
    toFuture(vdr.resolveCredDef(fqCredDefId))
  }

  override def resolveCredDef(fqCredDefId: FQCredDefId,
                              cacheOptions: CacheOptions): Future[VdrCredDef] = {
    toFuture(vdr.resolveCredDef(fqCredDefId, cacheOptions))
  }

  override def prepareDid(txnSpecificParams: TxnSpecificParams,
                          submitterDid: DidStr,
                          endorser: Option[String]): Future[VdrResults.PreparedTxnResult] = {
    toFuture(vdr.prepareDID(txnSpecificParams, submitterDid, endorser.orNull))
  }

  override def prepareSchema(txnSpecificParams: TxnSpecificParams,
                             submitterDid: DidStr,
                             endorser: Option[String]): Future[VdrResults.PreparedTxnResult] = {
    toFuture(vdr.prepareSchema(txnSpecificParams, submitterDid, endorser.orNull))
  }

  override def prepareCredDef(txnSpecificParams: TxnSpecificParams,
                              submitterDid: DidStr,
                              endorser: Option[String]): Future[VdrResults.PreparedTxnResult] = {
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