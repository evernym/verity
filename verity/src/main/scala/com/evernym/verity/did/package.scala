package com.evernym.verity

import com.evernym.verity.util.Base58Util

import scala.util.Success

package object did {

  type DidStr = String
  type VerKeyStr = String

  val VALID_DID_BYTE_LENGTH = 16
  val VALID_VER_KEY_BYTE_LENGTH = 32

  def validateDID(did: DidStr): Unit = {
    val decodedDID = Base58Util.decode(did)
    decodedDID match {
      case Success(d) if d.length == VALID_DID_BYTE_LENGTH => //valid did
      case _ => throw new RuntimeException("invalid did: " + did)
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
      validateDID(did)
      validateVerKey(verKey)
    }
  }

}
