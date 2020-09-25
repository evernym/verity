package com.evernym.verity.transformer


import java.security.{Key, MessageDigest}
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

import com.evernym.verity.Exceptions.EventDecryptionErrorException
import com.evernym.verity.config.CommonConfig.SALT_EVENT_ENCRYPTION
import com.evernym.verity.config.AppConfigWrapper
import com.evernym.verity.util.Serialization


trait Encryptor {

  val transformation: String
  val algorithm: String
  val salt: String

  def encrypt(value: Any, key: Key): Array[Byte] = {
    val cipher: Cipher = Cipher.getInstance(transformation)
    cipher.init(Cipher.ENCRYPT_MODE, key)
    cipher.doFinal(Serialization.serialise(value))
  }

  def decrypt(encryptedValue: Array[Byte], key: Key): Any = {
    try {
      val cipher: Cipher = Cipher.getInstance(transformation)
      cipher.init(Cipher.DECRYPT_MODE, key)
      Serialization.deserialise(cipher.doFinal(encryptedValue))
    } catch {
      case e: Exception =>
        throw new EventDecryptionErrorException(Option(e.getMessage))
    }
  }

  def keyToSpec(key: String): SecretKeySpec = {
    var keyBytes: Array[Byte] = (salt + key).getBytes("UTF-8")
    val sha: MessageDigest = MessageDigest.getInstance("SHA-512")
    keyBytes = sha.digest(keyBytes)
    keyBytes = java.util.Arrays.copyOf(keyBytes, 16)
    new SecretKeySpec(keyBytes, algorithm)
  }

}

trait AESEncryptorTransformer extends Encryptor with TransformerBase {

  override val transformation = "AES/ECB/PKCS5PADDING"
  override val algorithm: String = "AES"

  override def pack(data: Any, secret: Any): TransformedData = {
    val secretKey = keyToSpec(secret.asInstanceOf[String])
    val encryptedEvt = super.encrypt(data, secretKey)
    TransformedData(encryptedEvt)
  }

  override def unpack(transformedEvent: TransformedData, secret: Any): Any = {
    val key = keyToSpec(secret.asInstanceOf[String])
    val anyDt = transformedEvent.data
    super.decrypt(anyDt, key)
  }

}

object EventDataTransformer extends AESEncryptorTransformer {
  override lazy val salt: String = AppConfigWrapper.getConfigStringReq(SALT_EVENT_ENCRYPTION)

  override def unpack(transformedEvent: TransformedData, secret: Any): Any = {
    super.unpack(transformedEvent, secret)
  }
}
