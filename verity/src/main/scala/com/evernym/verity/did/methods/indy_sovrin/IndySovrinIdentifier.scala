package com.evernym.verity.did.methods.indy_sovrin

import com.evernym.verity.did.exception.UnrecognizedMethodIdentifierException
import com.evernym.verity.did.methods.NamespaceIdentifier

/**
 *
 * @param didStr "did:indy:sovrin:123", "did:indy:sovrin:stage:123", "did:indy:sovrin:builder:123"
 * @param method "indy"
 * @param methodIdentifier "sovrin:123", "sovrin:stage:123", "sovrin:builder:123"
 */
case class IndySovrinIdentifier(didStr: String, method: String, methodIdentifier: String)
  extends NamespaceIdentifier {

  private val splitted: Array[String] = methodIdentifier.split(":")

  override val namespace: String = splitted.length match {
    case 3 if splitted(0) == "sovrin" =>  s"$method:sovrin:${splitted(1)}"      //sovrin:stage:123, sovrin:builder:123
    case 2 if splitted(0) == "sovrin" =>  s"$method:sovrin"                     //sovrin:123
    case _ => throw new UnrecognizedMethodIdentifierException(method, methodIdentifier)
  }

  override val namespaceIdentifier: String =
    if (splitted.last.matches("^[1-9A-HJ-NP-Za-km-z]{21,22}$")) splitted.last
    else throw new UnrecognizedMethodIdentifierException(method, methodIdentifier)
}
