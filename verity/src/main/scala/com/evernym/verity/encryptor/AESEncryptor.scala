package com.evernym.verity.encryptor

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

import com.evernym.verity.Exceptions.EventDecryptionErrorException
import com.evernym.verity.config.CommonConfig.SALT_EVENT_ENCRYPTION
import com.evernym.verity.config.AppConfigWrapper


trait Encryptor {

  val cipherTransformation: String
  val algorithm: String
  val salt: String

  def encrypt(value: Array[Byte], secret: String): Array[Byte] = {
    val cipher: Cipher = Cipher.getInstance(cipherTransformation)
    cipher.init(Cipher.ENCRYPT_MODE, keyToSpec(secret))
    cipher.doFinal(value)
  }

  def decrypt(value: Array[Byte], secret: String): Array[Byte] = {
    val cipher: Cipher = Cipher.getInstance(cipherTransformation)
    try {
      cipher.init(Cipher.DECRYPT_MODE, keyToSpec(secret))
      cipher.doFinal(value)
    } catch {
      case e: RuntimeException =>
        throw new EventDecryptionErrorException(Option(e.getMessage))
    }
  }

  protected def keyToSpec(key: String): SecretKeySpec = {
    var keyBytes: Array[Byte] = (salt + key).getBytes("UTF-8")
    val sha: MessageDigest = MessageDigest.getInstance("SHA-512")
    keyBytes = sha.digest(keyBytes)
    keyBytes = java.util.Arrays.copyOf(keyBytes, 16)
    new SecretKeySpec(keyBytes, algorithm)
  }

}

trait AESEncryptor extends Encryptor {
  override val cipherTransformation = "AES/ECB/PKCS5PADDING"
  override val algorithm: String = "AES"
}

object PersistentDataEncryptor extends AESEncryptor {
  override lazy val salt: String = AppConfigWrapper.getStringReq(SALT_EVENT_ENCRYPTION)
}
