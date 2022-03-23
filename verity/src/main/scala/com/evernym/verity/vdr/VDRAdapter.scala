package com.evernym.verity.vdr

import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess.SIGN_ED25519_SHA512_SINGLE

import scala.concurrent.Future

//interface to be used by verity code to interact with VDR/Ledger services
trait VDRAdapter {

  def ping(namespaces: List[Namespace]): Future[PingResult]

  def prepareSchemaTxn(schemaJson: String,
                       fqSchemaId: FQSchemaId,
                       submitterDID: VdrDid,
                       endorser: Option[String]): Future[PreparedTxn]



  def prepareCredDefTxn(credDefJson: String,
                        fqCredDefId: FQCredDefId,
                        submitterDID: VdrDid,
                        endorser: Option[String]): Future[PreparedTxn]

  def submitTxn(preparedTxn: PreparedTxn,
                signature: Array[Byte],
                endorsement: Array[Byte]): Future[SubmittedTxn]

  def resolveSchema(schemaId: FQSchemaId): Future[Schema]

  def resolveCredDef(credDefId: FQCredDefId): Future[CredDef]

  def resolveDID(fqDid: FQDid): Future[DidDoc]
}


case class LedgerStatus(reachable: Boolean)

case class PingResult(status: Map[Namespace, LedgerStatus])

case class PreparedTxn(namespace: Namespace,
                       signatureSpec: SignatureSpec,
                       txnBytes: Array[Byte],
                       bytesToSign: Array[Byte],
                       endorsementSpec: EndorsementSpec) {

  def signatureType: String = if (signatureSpec == "") SIGN_ED25519_SHA512_SINGLE else signatureSpec
}

case class SubmittedTxn(response: String)

case class Schema(fqId: FQSchemaId, json: String)

case class CredDef(fqId: FQCredDefId, schemaId: FQSchemaId, json: String)

case class DidDoc(fqId: FQDid, verKey: VerKeyStr, endpoint: Option[String])

