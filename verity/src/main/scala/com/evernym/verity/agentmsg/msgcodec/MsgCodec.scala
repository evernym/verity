package com.evernym.verity.agentmsg.msgcodec

import com.evernym.verity.actor.agent.{MsgOrders, MsgPackFormat, Thread}
import com.evernym.verity.agentmsg.msgfamily.pairwise.MsgExtractor.JsonStr
import com.evernym.verity.actor.agent.TypeFormat.STANDARD_TYPE_FORMAT
import com.evernym.verity.protocol.engine._

import scala.reflect.ClassTag


trait MsgCodec {
  /**
    *
    * @param jsonStr message json string
    * @param mpf message pack format
    * @param legacyType this is for legacy agent msgs where in few scenarios
    *                   the meta data related information is available in a 'wrapper msg' instead
    *                   of the supplied 'msg'
    * @param protoReg protocol registry
    * @return
    */
  def decode(jsonStr: String, mpf: MsgPackFormat, legacyType: Option[MsgType])(implicit protoReg: ProtocolRegistry[_]): MsgPlusMeta = {
    //TODO JL shouldn't need to parse the json twice
    val nativeMsg = fromJson(jsonStr, legacyType)
    val metadata = extractMetadata(jsonStr, mpf)
    MsgPlusMeta(nativeMsg, metadata)
  }

  /**
    * The type of a JSON Document that is native to the serialization library.
    * For example, for a Jackson serializer, [[Document]] would be ObjectNode.
    */
  type Document

//  def msgTypeFromJson(json: String): MsgType

  def msgTypeFromDoc(doc: Document): MsgType

  /**
    * Takes a supplied document and returns a copy with type and thread attributes added.
    */
  def addLegacyMetaDataToDoc(doc: Document, msgType: MsgType, threadId: ThreadId): Document

  def addMetaDataToDoc(doc: Document, msgType: MsgType, msgId: MsgId, threadId: ThreadId, msgOrders: Option[MsgOrders]=None): Document

  def toJson[A](value: A): String

  /**
    * Converts an object into a document.
    */
  def toDocument[A](value: A): Document

  def docFromStrUnchecked(jsonStr: String): Document

  def nativeMsgFromDocUnchecked(doc: Document, nativeType: Class[_]): Any

  final def docFromStr(jsonStr: String): Document = {
    try {
      docFromStrUnchecked(jsonStr)
    } catch {
      case e: Exception => throw new JsonParsingException(e)
    }
  }

  final def nativeMsgFromDoc(doc: Document, nativeType: Class[_]): Any = {
    try {
      Option(nativeMsgFromDocUnchecked(doc, nativeType)).getOrElse(
        throw new NativeMsgValidationException("error while creating native msg from doc: " + doc))
    } catch {
      case e: Exception =>
        throw new DecodingException(doc.toString, nativeType.getName, e)
    }
  }

  def extractMetadata(jsonStr: JsonStr, msgPackFormat: MsgPackFormat): MsgMetadata

  def toAgentMsg[A](value: A, msgId: MsgId, threadId: ThreadId, msgFamily: MsgFamily): AgentJsonMsg = {
    toAgentMsg(value, msgId, threadId, msgFamily, STANDARD_TYPE_FORMAT, None)
  }

  def toAgentMsg[A](value: A, msgId: MsgId, threadId: ThreadId, msgFamily: MsgFamily,
                    msgTypeFormat: TypeFormatLike, msgOrders: Option[MsgOrders]=None): AgentJsonMsg = {
    val msgType = msgFamily.msgType(value.getClass)
    val doc = value match {
      case s: String  => docFromStr(s)
      case ojb        => toDocument(ojb)
    }

    val typedJsonMsg = msgTypeFormat match {
      case _:NoopTypeFormat     => doc
      case _:LegacyTypeFormat   => addLegacyMetaDataToDoc(doc, msgType, threadId)
      case _:StandardTypeFormat => addMetaDataToDoc(doc, msgType, msgId, threadId, msgOrders)
    }

    AgentJsonMsg(typedJsonMsg.toString, msgType)
  }

  /**
    *
    * @param json json msg to be mapped to a corresponding native msg
    * @param legacyType Some legacy agent msgs are not self describing. The `type` information is contained at
    *                   another layer of the message packing. In these cases it is necessary to include `type`
    *                   information with this conversion.
    * @param protoReg
    * @return
    */
  def fromJson(json: String, legacyType: Option[MsgType]=None)(implicit protoReg: ProtocolRegistry[_]): Any = {
    val msgType = legacyType
      .getOrElse(
        msgTypeFromDoc(docFromStr(json))
      )
    val nativeClassType = protoReg.getNativeClassType(msgType)
    fromJson(json, nativeClassType)
  }

  def fromJson[T: ClassTag](jsonStr: String): T = {
    val clazz = implicitly[ClassTag[T]].runtimeClass
    fromJson(jsonStr, clazz).asInstanceOf[T]
  }

  def fromJson(jsonStr: String, nativeClassType: Class[_]): Any = {
    val doc = docFromStr(jsonStr)
    fromJson(doc, nativeClassType)
  }

  def fromJson(doc: Document, nativeClassType: Class[_]): Any = {
    val nativeMsg = nativeMsgFromDoc(doc, nativeClassType)
    //validate native object
    nativeMsg match {
      case mb: MsgBase => mb.validate()
      case _ => // do nothing
    }
    nativeMsg
  }
}


//TODO temporary until we merge this into TypedMsg
case class MsgPlusMeta(msg: Any, meta: MsgMetadata)


case class MsgMetadata(msgId: Option[MsgId], msgThread: Thread, forRelationship: Option[DID]) {
  def threadId: ThreadId = msgThread.thid.getOrElse(msgId.getOrElse(DEFAULT_THREAD_ID))
}

case class AgentJsonMsg(jsonStr: String, msgType: MsgType)

