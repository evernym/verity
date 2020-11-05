package com.evernym.verity.protocol.didcomm.decorators

import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.protocol.engine.{MsgFamily, ProtoDef}
import com.evernym.verity.util.{Base64Util, Util}

sealed trait AttachmentData

case class Base64(base64: String) extends AttachmentData

// All attachment descriptor fields except the data are listed as optional in the attachments RFC (RFC0017)
// some subset of these fields may be required in specific use cases
case class AttachmentDescriptor(`@id`: Option[String] = None,
                                `mime-type`: Option[String] = None,
                                data: Base64 = Base64(""),
                                filename: Option[String] = None,
                                lastmod_time: Option[String] = None,
                                byte_count: Option[Int] = None,
                                description: Option[String] = None
                               )

object AttachmentDescriptor {
  def buildAttachment[A](id: Option[String] = None,
                         payload: A,
                         mimeType: Option[String] = Some("application/json"),
                         filename: Option[String] = None,
                         lastmod_time: Option[String] = None,
                         byte_count: Option[Int] = None,
                         description: Option[String] = None
                        ): AttachmentDescriptor = {
    val json = payload match {
      case s: String => s
      case _ => DefaultMsgCodec.toJson(payload)
    }

    val encoded = Base64Util.getBase64Encoded(json.getBytes)

    AttachmentDescriptor(id, mimeType, Base64(encoded))
  }

  def buildProtocolMsgAttachment[A](id: String, threadId: String, msgFamily: MsgFamily, payload: A) = {
    val json = DefaultMsgCodec.toAgentMsg(payload, id, threadId, msgFamily).jsonStr

    val encoded = Base64Util.getBase64Encoded(json.getBytes)

    AttachmentDescriptor(Some(id), Some("application/didcomm-plain+json"), Base64(encoded))
  }

  def extractString(at: AttachmentDescriptor): String = {
    new String(Base64Util.getBase64Decoded(at.data.base64))
  }
}