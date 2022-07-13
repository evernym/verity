package com.evernym.verity.did.methods.sov

import com.evernym.verity.did.exception.UnrecognizedMethodIdentifierException
import com.evernym.verity.did.methods.MethodIdentifier

/**
 *
 * @param didStr "did:sov:123"
 * @param method "sov"
 * @param methodIdentifier "123"
 */
case  class SovIdentifier(didStr: String, method: String, methodIdentifier: String)
  extends MethodIdentifier {

  if (! methodIdentifier.matches("^[1-9A-HJ-NP-Za-km-z]{21,22}$")) {
    throw new UnrecognizedMethodIdentifierException(method, methodIdentifier)
  }
}
