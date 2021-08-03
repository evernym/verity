package com.evernym.verity

import com.evernym.verity.util.Base58Util

import scala.util.Success

package object did {

  type DidStr = String
  type VerKeyStr = String

  val VALID_DID_BYTE_LENGTH = 16
  val VALID_VER_KEY_BYTE_LENGTH = 32

  case class DidPair(did: DidStr, verKey: VerKeyStr) {
    // TODO this should not be needed but helps bridge difference during refactoring
    def toAgentDidPair: com.evernym.verity.actor.agent.DidPair = com.evernym.verity.actor.agent.DidPair(did, verKey)

    def validate(): Unit = {
      val decodedDID = Base58Util.decode(did)
      val decodedVerKey = Base58Util.decode(verKey)
      (decodedDID, decodedVerKey) match {
        case (Success(d), Success(vk)) if d.length == VALID_DID_BYTE_LENGTH && vk.length == VALID_VER_KEY_BYTE_LENGTH => //valid did pair
        case _ => throw new RuntimeException("invalid did pair: " + DidPair(did, verKey))
      }
    }
  }

}
