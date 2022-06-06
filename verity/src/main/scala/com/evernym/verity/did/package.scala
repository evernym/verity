package com.evernym.verity

import com.evernym.verity.did.exception.{UnableToIdentifyDIDMethodException, UnrecognizedDIDMethodException}
import com.evernym.verity.did.methods._
import com.evernym.verity.did.methods.indy_sovrin.DIDIndySovrin
import com.evernym.verity.did.methods.key.DIDKey
import com.evernym.verity.did.methods.sov.DIDSov
import com.evernym.verity.util.Base58Util

import scala.util.Success

package object did {

  type DidStr = String
  type VerKeyStr = String

  val VALID_DID_BYTE_LENGTH = 16
  val VALID_VER_KEY_BYTE_LENGTH = 32

  /**
   *
   * @param did a did string (for ex: did:sov:123, did:indy:sovrin:123, did:indy:sovrin:stage:123, 123 etc)
   * @return
   */
  def toDIDMethod(did: DidStr): DIDMethod = {
    val splitted = did.split(":")
    splitted.length match {
      case x if x >= 3 =>
        splitted(1) match {
          case "indy" => new DIDIndySovrin(did)     //TODO (VE-3368): is this look correct?
          case "sov"  => new DIDSov(did)
          case "key"  => new DIDKey(did)
          case _ => throw new UnrecognizedDIDMethodException(did, splitted(2))
        }
      case 1 =>
        new UnqualifiedDID(did)

      case _ =>
        throw new UnableToIdentifyDIDMethodException(did)

    }
  }


  def validateDID(did: Any): Unit = {
    did match {
      case u: UnqualifiedDID =>
        val decodedDID = Base58Util.decode(u.methodIdentifier.toString)
        decodedDID match {
          case Success(d) if d.length == VALID_DID_BYTE_LENGTH => //valid did
          case _ => throw new RuntimeException("invalid did: " + u)
        }
      case v: SelfValidated => // validation performed in class
      case x: DIDMethod => throw new UnrecognizedDIDMethodException(x.toString, x.method)
    }
  }

  def validateVerKey(verKey: VerKeyStr): Unit = {
    val decodedVerKey = Base58Util.decode(verKey)
    decodedVerKey match {
      case Success(vk) if vk.length == VALID_VER_KEY_BYTE_LENGTH => //valid verKey
      case _ => throw new RuntimeException("invalid verKey: " + verKey)
    }
  }

  case class DidPair(did: DidStr, verKey: VerKeyStr) {
    // TODO this should not be needed but helps bridge difference during refactoring
    def toAgentDidPair: com.evernym.verity.actor.agent.DidPair = com.evernym.verity.actor.agent.DidPair(did, verKey)

    def validate(): Unit = {
      validateDID(toDIDMethod(did))
      validateVerKey(verKey)
    }
  }

}
