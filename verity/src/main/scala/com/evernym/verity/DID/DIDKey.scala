package com.evernym.verity.DID

import com.evernym.verity.protocol.engine.VerKey
import com.evernym.verity.util.Base58Util

class DIDKey(val key: VerKey) extends DID{
  override val method: String = "key"
  override val identifier: String = "z"+Base58Util.encode(("ED01"+Base58Util.decode(key)).getBytes())
}
