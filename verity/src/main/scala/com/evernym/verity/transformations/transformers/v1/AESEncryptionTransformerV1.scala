package com.evernym.verity.transformations.transformers.v1

import com.evernym.verity.encryptor.PersistentDataEncryptor
import com.evernym.verity.transformations.transformers.<=>

/**
 * encrypts/decrypts given binary data
 *
 * @param secret a secret used to generate symmetric encryption key
 */
class AESEncryptionTransformerV1(secret: String)
  extends (Array[Byte] <=> Array[Byte]) {

  override val execute: Array[Byte] => Array[Byte] = { msg =>
    PersistentDataEncryptor.encrypt(msg, secret)
  }

  override val undo: Array[Byte] => Array[Byte] = { msg =>
    PersistentDataEncryptor.decrypt(msg, secret)
  }
}
