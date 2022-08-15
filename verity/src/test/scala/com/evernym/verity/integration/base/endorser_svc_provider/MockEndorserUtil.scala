package com.evernym.verity.integration.base.endorser_svc_provider

import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.did
import com.evernym.verity.did.DidStr

object MockEndorserUtil {

  val inactiveEndorserDid: DidStr = CommonSpecUtil.generateNewDid().did
  val activeEndorser: did.DidPair = CommonSpecUtil.generateNewDid()
  val activeEndorserDid: DidStr = activeEndorser.did

  val INDY_LEDGER_PREFIX: String = "did:indy:sovrin"

}
