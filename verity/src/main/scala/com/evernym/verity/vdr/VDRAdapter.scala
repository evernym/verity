package com.evernym.verity.vdr

import com.evernym.vdrtools.vdr.VdrResults.PreparedTxnResult
import com.evernym.verity.did.DidStr
import com.evernym.verity.vdr.service.PingResult

import scala.concurrent.Future

//interface to be used by verity code to interact with VDR/Ledger services
trait VDRAdapter {

  def ping(namespaces: List[Namespace]): Future[Map[String, PingResult]]

  def prepareSchemaTxn(txnSpecificParams: TxnSpecificParams,
                       submitterDid: DidStr,
                       endorser: Option[String]): Future[PreparedTxnResult]

  def prepareCredDefTxn(txnSpecificParams: TxnSpecificParams,
                        submitterDid: DidStr,
                        endorser: Option[String]): Future[PreparedTxnResult]

  def submitTxn(namespace: Namespace,
                txnBytes: Array[Byte],
                signatureSpec: String,
                signature: Array[Byte],
                endorsement: String): Future[TxnResult]

  def resolveSchema(schemaId: FQSchemaId): Future[Schema]

  def resolveCredDef(credDefId: FQCredDefId): Future[CredDef]

  def resolveDID(fqDid: FQDid): Future[Did]
}

