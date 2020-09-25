package com.evernym.verity.agentmsg.msgcodec

trait MsgCodecException

class NativeMsgValidationException(reason: String)
  extends RuntimeException("native message validation error: " + reason) with MsgCodecException

class JsonParsingException(cause: Throwable)
  extends RuntimeException("error parsing json" , cause) with MsgCodecException

class MsgTypeParsingException(typeStr: String)
  extends RuntimeException(s"error parsing type string: $typeStr") with MsgCodecException

class DecodingException(encoded: String, nativeType: String, cause: Exception)
  extends RuntimeException(s"error decoding object type $nativeType from $encoded", cause) with MsgCodecException

class MsgTypeException(typeStr: String, reason: Option[String] = None)
  extends RuntimeException(s"error error handling: $typeStr${reason.map("- "+ _)}") with MsgCodecException
