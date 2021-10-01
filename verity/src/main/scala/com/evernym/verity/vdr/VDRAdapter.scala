package com.evernym.verity.vdr

import com.evernym.verity.did.DidStr

import scala.concurrent.Future

//interface to be used by verity code to interact with VDR/Ledger services
trait VDRAdapter {
  def prepareSchemaTxn(schemaJson: String,
                       fqSchemaId: FQSchemaId,
                       submitterDID: DidStr,
                       endorser: Option[String]): Future[PreparedTxn]

  def submitTxn(preparedTxn: PreparedTxn,
                signature: Array[Byte],
                endorsement: Array[Byte]): Future[SubmittedTxn]
}

case class PreparedTxn(context: String,
                       signatureSpec: SignatureSpec,
                       bytesToSign: Array[Byte],
                       endorsementSpec: EndorsementSpec)

case class SubmittedTxn()

trait SignatureSpec
case object NoSignature extends SignatureSpec

trait EndorsementSpec
case object NoEndorsement extends EndorsementSpec
case class IndyEndorsement(endorserDID: DidStr) extends EndorsementSpec
