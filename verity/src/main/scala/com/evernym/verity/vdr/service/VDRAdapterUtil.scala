package com.evernym.verity.vdr.service

import com.evernym.verity.vdr.{EndorsementSpec, NoEndorsement, NoSignature, PreparedTxn, SignatureSpec}

//currently only contains data transformation related functions
object VDRAdapterUtil {

  def buildPreparedTxn(vdrTxn: VDR_PreparedTxn): PreparedTxn = {
    PreparedTxn(
      vdrTxn.context,
      buildSigSpec(vdrTxn.signatureSpec),
      vdrTxn.bytesToSign,
      buildEndorsementSpec(vdrTxn.endorsementSpec)
    )
  }

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
      case NoEndorsement => VDR_NoEndorsement
    }
  }
}