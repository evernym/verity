package com.evernym.verity.transformations.transformers.legacy

import com.evernym.verity.encryptor.PersistentDataEncryptor
import com.evernym.verity.transformations.transformers.<=>

/**
 * encrypts/decrypts given binary data
 *
 * @param secret a secret used to generate symmetric encryption key
 */
class LegacyAESEncryptionTransformer(secret: String, salt: String)
  extends (TransParam[Array[Byte]] <=> TransParam[Array[Byte]]) {
  val pde: PersistentDataEncryptor = new PersistentDataEncryptor(salt)

  override val execute: TransParam[Array[Byte]] => TransParam[Array[Byte]] = { param =>
    val encrypted = pde.encrypt(param.msg, secret)
    param.copy(msg = encrypted)
  }

  override val undo: TransParam[Array[Byte]] => TransParam[Array[Byte]] = { param =>
    val decrypted = pde.decrypt(param.msg, secret)
    param.copy(msg = decrypted)
  }

}
