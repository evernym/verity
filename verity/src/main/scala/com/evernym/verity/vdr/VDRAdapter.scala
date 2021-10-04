package com.evernym.verity.vdr

import com.evernym.verity.did.DidStr

import scala.concurrent.Future

//interface to be used by verity code to interact with VDR/Ledger services
trait VDRAdapter {

  def ping(namespaces: List[Namespace]): Future[PingResult]

  def prepareSchemaTxn(schemaJson: String,
                       fqSchemaId: FQSchemaId,
                       submitterDID: DidStr,
                       endorser: Option[String]): Future[PreparedTxn]

  def submitTxn(preparedTxn: PreparedTxn,
                signature: Array[Byte],
                endorsement: Array[Byte]): Future[SubmittedTxn]

  def resolveSchema(schemaId: FQSchemaId): Future[Schema]

  def prepareCredDefTxn(credDefJson: String,
                       fqCredDefId: FQCredDefId,
                       submitterDID: DidStr,
                       endorser: Option[String]): Future[PreparedTxn]
}


case class LedgerStatus(reachable: Boolean)
case class PingResult(status: Map[Namespace, LedgerStatus])

case class PreparedTxn(context: String,
                       signatureSpec: SignatureSpec,
                       bytesToSign: Array[Byte],
                       endorsementSpec: EndorsementSpec)

case class SubmittedTxn()

case class Schema(fqId: FQSchemaId, json: String)

//below will change to some constants/enums when we have actual VDRTools library available for integration
trait SignatureSpec
case object NoSignature extends SignatureSpec

trait EndorsementSpec
case object NoEndorsement extends EndorsementSpec
case object IndyEndorsement extends EndorsementSpec

