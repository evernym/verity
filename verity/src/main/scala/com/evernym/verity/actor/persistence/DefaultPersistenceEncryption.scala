package com.evernym.verity.actor.persistence

import com.evernym.verity.config.AppConfig
import com.evernym.verity.util.Util
import com.evernym.verity.util.Util.getEventEncKey

trait DefaultPersistenceEncryption {

  this : BasePersistentActor =>

  implicit def appConfig: AppConfig

  def getEventEncryptionKey: String = {
    DefaultPersistenceEncryption.getEventEncryptionKey(entityId, appConfig)
  }

  lazy val persistenceEncryptionKey: String = getEventEncryptionKey
}

object DefaultPersistenceEncryption {
  def getEventEncryptionKey(entityId: String, appConfig: AppConfig): String = {
    //NOTE: This logic should not be changed unless we know its impact
    val secret = Util.saltedHashedName(entityId + "actor-wallet", appConfig)
    getEventEncKey(secret, appConfig)
  }
}