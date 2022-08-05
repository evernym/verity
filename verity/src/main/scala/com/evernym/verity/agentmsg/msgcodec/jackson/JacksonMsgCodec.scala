package com.evernym.verity.agentmsg.msgcodec.jackson

import com.evernym.verity.constants.Constants._
import com.evernym.verity.actor.agent.{MsgOrders, MsgPackFormat}
import com.evernym.verity.did.didcomm.v1.Thread
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_MSG_PACK
import com.evernym.verity.agentmsg.msgcodec.{MsgCodec, MsgMetadata, MsgTypeException}
import com.evernym.verity.agentmsg.msgfamily.pairwise.MsgExtractor.JsonStr
import com.evernym.verity.agentmsg.msgpacker.AgentMsgParseUtil
import com.evernym.verity.did.didcomm.v1.messages.{MsgFamily, MsgId, MsgType}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.util.OptionUtil
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.{DeserializationFeature, JsonNode, ObjectMapper, SerializationFeature}
import com.fasterxml.jackson.datatype.jsonorg.JsonOrgModule
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.scala.DefaultScalaModule


object JacksonMsgCodec extends MsgCodec {

  type Document = JsonNode

  val jacksonMapper = new ObjectMapper()
  jacksonMapper.registerModule(DefaultScalaModule)
  jacksonMapper.configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, false)
  jacksonMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
  jacksonMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
  jacksonMapper.setSerializationInclusion(Include.NON_ABSENT)

  //to solve zoned date time deserialization issue related to precision loss (see MsgCodecSpec)
  jacksonMapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

  //simple module for custom serializers and deserializers
  val serializersModule = new SimpleModule("serializers")
  //this is to override Jackson's default byte array serialization and deserialization which uses Base64
  serializersModule.addSerializer(classOf[Array[Byte]], new ArrayByteSerializer)

  //register modules
  jacksonMapper.registerModule(serializersModule)
  jacksonMapper.registerModule(new JsonOrgModule())   //to handle JSONObject serialization/deserialization
  jacksonMapper.registerModule(new JavaTimeModule())  //to handle Java time data types serialization/deserialization

  def toJson[A](value: A): String = {
    jacksonMapper.writeValueAsString(value)
  }

  def toDocument[A](value: A): Document = {
    val jsonStr = toJson(value)
    jacksonMapper.readTree(jsonStr)
  }

  override def addLegacyMetaDataToDoc(doc: JsonNode, msgType: MsgType, threadId: ThreadId): JsonNode = {
    addLegacyMsgType(doc, msgType)
    addThread(doc, msgType, threadId)
  }

  def addLegacyMsgType(doc: JsonNode, msgType: MsgType): JsonNode = {
    val typeJsonObj = jacksonMapper.readTree(s"""{"$MSG_NAME":"${msgType.msgName}","$VER":"$MTV_1_0"}""")
    asObject(doc, msgType).set(`@TYPE`, typeJsonObj)
  }

  override def addMetaDataToDoc(doc: JsonNode, msgType: MsgType, msgId: MsgId, threadId: ThreadId, msgOrders: Option[MsgOrders]=None): JsonNode = {
    val typeStr = MsgFamily.typeStrFromMsgType(msgType)
    asObject(doc, msgType).put(`@TYPE`, typeStr)
    asObject(doc, msgType).put(`@id`, msgId)
    addThread(doc, msgType, threadId, msgOrders)
  }

  private def addThread(doc: JsonNode, msgType: MsgType, threadId: ThreadId, msgOrders: Option[MsgOrders]=None): JsonNode = {
    val threadObjectNode = jacksonMapper.createObjectNode()
    threadObjectNode.put(THREAD_ID, threadId)
    if (doc.hasNonNull("~thread") && doc.get("~thread").hasNonNull("pthid")) {
      threadObjectNode.set(PARENT_THREAD_ID, doc.get("~thread").get("pthid"))
    }
    msgOrders.foreach { pmod =>
      if (pmod.senderOrder >= 0) {
        threadObjectNode.put(SENDER_ORDER, pmod.senderOrder)
      }
      if (pmod.receivedOrders.nonEmpty) {
        val receivedOrderNode = jacksonMapper.createObjectNode()
        pmod.receivedOrders.foreach { case (from, order) =>
          receivedOrderNode.put(from, order)
        }
        threadObjectNode.set(RECEIVED_ORDERS, receivedOrderNode)
      }
    }
    asObject(doc, msgType).set(THREAD, threadObjectNode)
  }

  private def asObject(doc: JsonNode, msgType: MsgType): ObjectNode = {
    if (doc.isObject)
      doc.asInstanceOf[ObjectNode]
    else
      throw new MsgTypeException(msgType.toString, Some("is not a JSON object"))
  }

  def docFromStrUnchecked(jsonStr: String): JsonNode = {
    jacksonMapper.readTree(jsonStr)
  }

  def msgTypeFromJson(json: String): MsgType = msgTypeFromDoc(docFromStr(json))

  def msgTypeFromDoc(doc: JsonNode): MsgType = {
    AgentMsgParseUtil.msgFamilyDetail(doc.toString).msgType
  }

  def nativeMsgFromDocUnchecked(doc: JsonNode, nativeType: Class[_]): Any = {
    jacksonMapper.convertValue(doc, nativeType)
  }

  override def extractMetadata(jsonStr: JsonStr, msgPackFormat: MsgPackFormat): MsgMetadata = {
    val jsonNode = jacksonMapper.readTree(jsonStr)
    val msgId = Option(jsonNode.get(`@id`))
      .map(_.textValue())

    val threadId = Option(jsonNode.get(THREAD))
      .flatMap(th => Option(th.get(THREAD_ID))
        .map(_.asText()))

    val forRelationship = msgPackFormat match {
      case MPF_MSG_PACK  => None
      case _ =>
        Option(
          jacksonMapper
            .readTree(jsonStr)
            .get(FOR_RELATIONSHIP)
        )
          .filter(_.isTextual)
          .map(_.asText())
          .flatMap(OptionUtil.blankOption)
    }

    MsgMetadata(msgId, Thread(threadId), forRelationship)
  }

}
