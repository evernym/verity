package com.evernym.verity.did.methods.key

import com.evernym.verity.did.methods.MethodIdentifier

/**
 *
 * @param didStr "123", "did:key:123" etc
 * @param method "key"
 * @param methodIdentifier "123"
 */
case class KeyIdentifier(didStr: String, method: String, methodIdentifier: String) extends MethodIdentifier
