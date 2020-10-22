package com.evernym.verity.transformations.transformers

import com.evernym.verity.encryptor.PersistentDataEncryptor

/**
 * encrypts/decrypts given binary data
 *
 * @param secret a secret used to generate symmetric encryption key
 */
class AESEncryptionTransformer(secret: String)
  extends (Array[Byte] <=> Array[Byte]) {

  override val execute: Array[Byte] => Array[Byte] = { msg =>
    PersistentDataEncryptor.encrypt(msg, secret)
  }

  override val undo: Array[Byte] => Array[Byte] = { msg =>
    PersistentDataEncryptor.decrypt(msg, secret)
  }
}
