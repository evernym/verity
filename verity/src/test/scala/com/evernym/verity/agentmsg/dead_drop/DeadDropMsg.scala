package com.evernym.verity.agentmsg.dead_drop

import com.evernym.verity.Signature
import com.evernym.verity.protocol.engine.VerKey

case class GetDeadDropMsg(`@type`: String, recoveryVerKey: VerKey, address: String, locator: String, locatorSignature: Signature)
