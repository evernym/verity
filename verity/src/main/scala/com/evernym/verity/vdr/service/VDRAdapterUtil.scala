package com.evernym.verity.vdr.service

import com.evernym.verity.vdr._

//currently only contains data transformation related functions
object VDRAdapterUtil {

  //implementation specific objects to interface objects converters

  def buildPreparedTxn(vdrTxn: VDR_PreparedTxn): PreparedTxn = {
    PreparedTxn(
      vdrTxn.context,
      buildSigSpec(vdrTxn.signatureSpec),
      vdrTxn.bytesToSign,
      buildEndorsementSpec(vdrTxn.endorsementSpec)
    )
  }

  def buildSchema(vdrSchema: VDR_Schema): Schema = {
    Schema(
      vdrSchema.schemaId,
      new String(vdrSchema.payload)
    )
  }

  def buildCredDef(vdrCredDef: VDR_CredDef) : CredDef = {
    CredDef(
      vdrCredDef.credDefID,
      vdrCredDef.schemaId,
      new String(vdrCredDef.payload)
    )
  }

  def buildDidDoc(vdrDidDoc: VDR_DidDoc): DidDoc = {
    DidDoc(
      vdrDidDoc.id,
      vdrDidDoc.verKeyStr,
      vdrDidDoc.endpoint
    )
  }

  def buildPingResult(vdrPingResult: VDR_PingResult): PingResult = {
    PingResult(vdrPingResult.status.mapValues(e => LedgerStatus(e.reachable)))
  }

  //interface objects to implementation specific objects converters

  def buildVDRPreparedTxn(txn: PreparedTxn): VDR_PreparedTxn = {
    VDR_PreparedTxn(
      txn.context,
      buildVDRSigSpec(txn.signatureSpec),
      txn.bytesToSign,
      buildVDREndorsementSpec(txn.endorsementSpec)
    )
  }

  private def buildSigSpec(vdrSigSpec: VDR_SignatureSpec): SignatureSpec = {
    vdrSigSpec match {
      case VDR_NoSignature => NoSignature
    }
  }

  private def buildEndorsementSpec(vdrEndorsementSpec: VDR_EndorsementSpec): EndorsementSpec = {
    vdrEndorsementSpec match {
      case VDR_NoEndorsement => NoEndorsement
    }
  }

  private def buildVDRSigSpec(sigSpec: SignatureSpec): VDR_SignatureSpec = {
    sigSpec match {
      case NoSignature => VDR_NoSignature
    }
  }

  private def buildVDREndorsementSpec(endorsementSpec: EndorsementSpec): VDR_EndorsementSpec = {
    endorsementSpec match {
      case NoEndorsement    => VDR_NoEndorsement
      case IndyEndorsement  => VDR_IndyEndorsement
    }
  }
}
