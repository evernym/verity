package com.evernym.verity.agentmsg.dead_drop

import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.util2.Signature

case class GetDeadDropMsg(`@type`: String, recoveryVerKey: VerKeyStr, address: String, locator: String, locatorSignature: Signature)
