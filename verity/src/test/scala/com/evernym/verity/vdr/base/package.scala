package com.evernym.verity.vdr

package object base {

  val SOV_LEDGER_NAME = "sov"
  val DEFAULT_VDR_NAMESPACE = s"$SOV_LEDGER_NAME"                 //"sov"
  val VDR_DID_SOV_NAMESPACE = s"$DID_PREFIX:$SOV_LEDGER_NAME"     //"did:sov"
}
