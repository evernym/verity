package com.evernym.verity.vdr.service

import com.evernym.vdrtools.vdr.{VDR, VdrResults}
import com.evernym.verity.did.DidStr
import com.evernym.verity.vdr._

import scala.compat.java8.FutureConverters.{toScala => toFuture}
import scala.concurrent.Future

//A thin wrapper around VDRTools API for production code
class VdrToolsImpl(val vdr: VDR) extends VdrTools {


  override def ping(namespaceList: List[String]): Future[Map[String, PingResult]] = {
    //todo will be changed in future vdrtools version
    toFuture(vdr.ping(namespaceList.mkString("[\"", "\",\"" ,"\"]")).thenApply(_ => Map.empty))
  }

  override def resolveDid(fqDid: FQDid): Future[Did] = {
    toFuture(vdr.resolveDID(fqDid))
  }

  override def resolveDid(fqDid: FQDid,
                          cacheOptions: CacheOptions): Future[Did] = {
    // todo not implemented yet
    toFuture(vdr.resolveDID(fqDid))
  }

  override def resolveSchema(fqSchemaId: FQSchemaId): Future[Schema] = {
    toFuture(vdr.resolveSchema(fqSchemaId))
  }

  override def resolveSchema(fqSchemaId: FQSchemaId,
                             cacheOptions: CacheOptions): Future[Schema] = {
    // todo not implemented yet
    toFuture(vdr.resolveSchema(fqSchemaId))
  }

  override def resolveCredDef(fqCredDefId: FQCredDefId): Future[CredDef] = {
    toFuture(vdr.resolveCredDef(fqCredDefId))
  }

  override def resolveCredDef(fqCredDefId: FQCredDefId,
                              cacheOptions: CacheOptions): Future[CredDef] = {
    // todo not implemented yet
    toFuture(vdr.resolveCredDef(fqCredDefId))
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
    // todo param order will change in future vdrtools releases
    toFuture(vdr.submitTxn(namespace, signatureSpec, txnBytes, signature, endorsement))
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