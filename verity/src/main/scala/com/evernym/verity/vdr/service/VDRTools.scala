package com.evernym.verity.vdr.service

import com.evernym.verity.did.DidStr
import com.evernym.verity.vdr.{FQSchemaId, Namespace}

import scala.concurrent.Future

trait VDRTools {
  def registerIndyLedger(namespaces: List[Namespace],
                         genesisTxnFilePath: String,
                         taaConfig: Option[TAAConfig]): Future[VDR_LedgerRegistered]

  def prepareSchemaTxn(schemaJson: String,
                       fqSchemaId: FQSchemaId,
                       submitterDid: DidStr,
                       endorser: Option[String]): Future[VDR_PreparedTxn]

  def submitTxn(preparedTxn: VDR_PreparedTxn,
                signature: Array[Byte],
                endorsement: Array[Byte]): Future[VDR_SubmittedTxn]

  def resolveSchema(schemaId: FQSchemaId): Future[VDR_Schema]
}

//TODO: most of the below parameters will be removed once corresponding library objects are available to use
case class VDR_LedgerRegistered()

case class VDR_PreparedTxn(context: String,
                           signatureSpec: VDR_SignatureSpec,
                           bytesToSign: Array[Byte],
                           endorsementSpec: VDR_EndorsementSpec)

case class VDR_SubmittedTxn()

case class VDR_Schema(schemaId: FQSchemaId, payload: Array[Byte])

trait VDR_SignatureSpec
case object VDR_NoSignature extends VDR_SignatureSpec

trait VDR_EndorsementSpec
case object VDR_NoEndorsement extends VDR_EndorsementSpec
case object VDR_IndyEndorsement extends VDR_EndorsementSpec



