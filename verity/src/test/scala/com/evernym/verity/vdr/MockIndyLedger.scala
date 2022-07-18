package com.evernym.verity.vdr

import com.evernym.vdrtools.vdr.VdrParams.TaaConfig
import com.evernym.verity.vdr.base.InMemLedger

//may override base implementations as per requirement/need
case class MockIndyLedger(genesisTxnData: String,
                          taaConfig: Option[TaaConfig])
  extends InMemLedger