package com.evernym.verity.vdr

package object base {
  private val MOCK_LEDGER_NAME_SOVRIN = "sov"
  val MOCK_VDR_SOV_NAMESPACE = s"$MOCK_LEDGER_NAME_SOVRIN"                          //"sov"
  val MOCK_VDR_DID_SOV_NAMESPACE = s"$SCHEME_NAME_DID:$MOCK_LEDGER_NAME_SOVRIN"     //"did:sov"
}
