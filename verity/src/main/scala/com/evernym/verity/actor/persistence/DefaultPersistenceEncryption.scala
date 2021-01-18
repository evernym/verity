package com.evernym.verity.actor.persistence

import com.evernym.verity.config.AppConfig
import com.evernym.verity.util.Util
import com.evernym.verity.util.Util.getEventEncKey

trait DefaultPersistenceEncryption {

  this : BasePersistentActor =>

  implicit def appConfig: AppConfig

  def getEventEncryptionKeyWithoutWallet: String = {
    DefaultPersistenceEncryption.getEventEncryptionKeyWithoutWallet(entityId, appConfig)
  }

  lazy val persistenceEncryptionKey: String = getEventEncryptionKeyWithoutWallet
}

object DefaultPersistenceEncryption {
  def getEventEncryptionKeyWithoutWallet(entityId: String, appConfig: AppConfig): String = {
    //NOTE: This logic should not be changed unless we know its impact
    val secret = Util.saltedHashedName(entityId + "actor-wallet", appConfig)
    getEventEncKey(secret, appConfig)
  }
}