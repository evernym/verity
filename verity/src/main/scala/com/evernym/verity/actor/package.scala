package com.evernym.verity

import akka.actor.Props
import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.Constants._
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.util.Base58Util

import scala.util.Success

package object actor {
  trait HasProps {
    def props(implicit conf: AppConfig): Props
  }

  trait DidPairBase {
    def DID: DID
    def verKey: VerKey

    def validate(): Unit = {
      val decodedDID = Base58Util.decode(DID)
      val decodedVerKey = Base58Util.decode(verKey)
      (decodedDID, decodedVerKey) match {
        case (Success(d), Success(vk)) if d.length == VALID_DID_BYTE_LENGTH && vk.length == VALID_VER_KEY_BYTE_LENGTH => //valid did pair
        case _ => throw new RuntimeException("invalid did pair: " + DidPair(DID, verKey))
      }
    }
  }
}
