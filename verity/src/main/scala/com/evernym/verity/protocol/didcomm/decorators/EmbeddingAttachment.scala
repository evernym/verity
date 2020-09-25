package com.evernym.verity.protocol.didcomm.decorators

import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.util.{Base64Util, Util}

sealed trait AttachmentData

case class Base64(base64: String) extends AttachmentData

case class EmbeddingAttachment(`@id`: String, `mime-type`: String, data: Base64)

object EmbeddingAttachment {
  def buildAttachment[A](id: String, payload: A, mimeType: String = "application/json"): EmbeddingAttachment = {
    val json = payload match {
      case s: String => s
      case _ => DefaultMsgCodec.toJson(payload)
    }

    val encoded = Base64Util.getBase64Encoded(json.getBytes)

    EmbeddingAttachment(id, mimeType, Base64(encoded))
  }

  def extractString(at: EmbeddingAttachment): String = {
    new String(Base64Util.getBase64Decoded(at.data.base64))
  }
}