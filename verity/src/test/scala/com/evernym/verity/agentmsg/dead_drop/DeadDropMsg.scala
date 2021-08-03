package com.evernym.verity.agentmsg.dead_drop

import com.evernym.verity.did.VerKey
import com.evernym.verity.util2.Signature

case class GetDeadDropMsg(`@type`: String, recoveryVerKey: VerKey, address: String, locator: String, locatorSignature: Signature)
