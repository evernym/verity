package com.evernym.verity.util

import java.util

import com.evernym.verity.actor.metrics.LibindyMetricsRecord
import com.evernym.verity.protocol.engine.util.DbcUtil
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.{DefaultScalaModule, ScalaObjectMapper}
import org.json.{JSONException, JSONObject}

//TODO move to MsgCodec?
object JsonUtil {

  private val jsonMapper = {
    val objectMapper = new ObjectMapper() with ScalaObjectMapper
    objectMapper.registerModule(DefaultScalaModule)
    objectMapper
  }

  def mapToJson(map: Map[String, Any]): String = {
    DbcUtil.requireNotNull(map, "map")
    jsonMapper.writeValueAsString(map)
  }

  def seqToJson(seq: Seq[String]): String = {
    DbcUtil.requireNotNull(seq, "map")
    jsonMapper.writeValueAsString(seq)
  }

  def deserializeJsonStringToMap[K, V](msg: String): Map[K, V] = {
    jsonMapper.readValue(msg, classOf[Map[K,V]])
  }

  def deserializeJsonStringToObject[T](metrics: String)(implicit m: Manifest[T]): T = {
    jsonMapper.readValue[T](metrics)
  }

  // TODO we need a better way to determine if a msg is JSON or MsgPack
  def deserializeAsJson(msg: Array[Byte]): JSONObject = {
    new JSONObject(new String(msg))
  }

  def getDeserializedJson(msg: Array[Byte]): Option[JSONObject] = {
    try {
      Option(deserializeAsJson(msg))
    } catch {
      case _: JSONException => None
      case e: Exception     => throw e
    }
  }

  def isDeserializableAsJson(msg: Array[Byte]): Boolean = {
    getDeserializedJson(msg) match {
      case Some(_) => true
      case None    => false
    }
  }

  def jsonArray(item: String): String = jsonArray(Set(item))

  def jsonArray(items: Set[String]): String = {
    """[""" + items.map(i => s""""$i"""").mkString(",") + """]"""
  }

}
