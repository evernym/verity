package com.evernym.verity.transformations.transformers.legacy

import com.evernym.verity.encryptor.PersistentDataEncryptor
import com.evernym.verity.transformations.transformers.<=>

/**
 * encrypts/decrypts given binary data
 *
 * @param secret a secret used to generate symmetric encryption key
 */
class LegacyAESEncryptionTransformer(secret: String)
  extends (TransParam[Array[Byte]] <=> TransParam[Array[Byte]]) {

  override val execute: TransParam[Array[Byte]] => TransParam[Array[Byte]] = { param =>
    val encrypted = PersistentDataEncryptor.encrypt(param.msg, secret)
    param.copy(msg = encrypted)
  }

  override val undo: TransParam[Array[Byte]] => TransParam[Array[Byte]] = { param =>
    val decrypted = PersistentDataEncryptor.decrypt(param.msg, secret)
    param.copy(msg = decrypted)
  }

}
