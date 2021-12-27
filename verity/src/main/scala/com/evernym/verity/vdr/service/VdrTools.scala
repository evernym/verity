package com.evernym.verity.vdr.service

import com.evernym.vdrtools.vdr.VdrResults.PreparedTxnResult
import com.evernym.verity.did.DidStr
import com.evernym.verity.vdr._

import scala.concurrent.Future

trait VdrTools {

  def ping(namespaceList: List[String]): Future[Map[String, PingResult]]

  def resolveDid(fqDid: FQDid): Future[Did]

  def resolveDid(fqDid: FQDid,
                 cacheOptions: CacheOptions): Future[Did]

  def resolveSchema(fqSchemaId: FQSchemaId): Future[Schema]

  def resolveSchema(fqSchemaId: FQSchemaId,
                    cacheOptions: CacheOptions): Future[Schema]

  def resolveCredDef(fqCredDefId: FQCredDefId): Future[CredDef]

  def resolveCredDef(fqCredDefId: FQCredDefId,
                     cacheOptions: CacheOptions): Future[CredDef]

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

// Todo: this classes will be introduced in future version of vdrtools
case class TaaConfig(text: String,
                     version: String,
                     taaDigest: String,
                     accMechType: String,
                     time: String)

case class CacheOptions(noCache: Boolean,
                        noUpdate: Boolean,
                        noStore: Boolean,
                        minFresh: Int)

case class PingResult( code: String,
                       message: String)
