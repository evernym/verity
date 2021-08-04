package com.evernym.verity.transformations.transformers.v1

import com.evernym.verity.encryptor.PersistentDataEncryptor
import com.evernym.verity.transformations.transformers.<=>

/**
 * encrypts/decrypts given binary data
 *
 * @param secret a secret used to generate symmetric encryption key
 */
class AESEncryptionTransformerV1(secret: String, salt: String)
  extends (Array[Byte] <=> Array[Byte]) {
  val pde: PersistentDataEncryptor = new PersistentDataEncryptor(salt)

  override val execute: Array[Byte] => Array[Byte] = { msg =>
    pde.encrypt(msg, secret)
  }

  override val undo: Array[Byte] => Array[Byte] = { msg =>
    pde.decrypt(msg, secret)
  }
}
