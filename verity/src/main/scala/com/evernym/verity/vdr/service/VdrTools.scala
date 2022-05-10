package com.evernym.verity.vdr.service

import com.evernym.vdrtools.vdr.VdrParams.CacheOptions
import com.evernym.vdrtools.vdr.VdrResults.{PingResult, PreparedTxnResult}
import com.evernym.verity.did.DidStr
import com.evernym.verity.vdr._

import scala.concurrent.Future

trait VdrTools {

  def ping(namespaceList: List[Namespace]): Future[Map[Namespace, PingResult]]

  def resolveDid(fqDid: FqDID): Future[VdrDid]

  def resolveDid(fqDid: FqDID,
                 cacheOptions: CacheOptions): Future[VdrDid]

  def resolveSchema(fqSchemaId: FqSchemaId): Future[VdrSchema]

  def resolveSchema(fqSchemaId: FqSchemaId,
                    cacheOptions: CacheOptions): Future[VdrSchema]

  def resolveCredDef(fqCredDefId: FqCredDefId): Future[VdrCredDef]

  def resolveCredDef(fqCredDefId: FqCredDefId,
                     cacheOptions: CacheOptions): Future[VdrCredDef]

  def prepareDid(txnSpecificParams: TxnSpecificParams,
                 submitterDid: DidStr,
                 endorser: Option[String]): Future[PreparedTxnResult]

  def prepareSchema(txnSpecificParams: TxnSpecificParams,
                    submitterDid: DidStr,
                    endorser: Option[String]): Future[PreparedTxnResult]

  def prepareCredDef(txnSpecificParams: TxnSpecificParams,
                     submitterDid: DidStr,
                     endorser: Option[String]): Future[PreparedTxnResult]

  def submitTxn(namespace: Namespace,
                txnBytes: Array[Byte],
                signatureSpec: String,
                signature: Array[Byte],
                endorsement: String): Future[TxnResult]

  def submitRawTxn(namespace: Namespace,
                   txnBytes: Array[Byte]): Future[TxnResult]

  def submitQuery(namespace: Namespace,
                  query: String): Future[TxnResult]
}