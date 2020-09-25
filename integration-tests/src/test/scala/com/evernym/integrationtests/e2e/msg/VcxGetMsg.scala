package com.evernym.integrationtests.e2e.msg

import org.json.{JSONArray, JSONObject}

import scala.collection.JavaConverters._
import scala.util.Try

object VcxGetMsg {
  def arrayToSeq(json: JSONArray): Seq[JSONObject] = {
    json.iterator()
      .asScala
      .toSeq
      .map{
        _.asInstanceOf[JSONObject]
      }
  }

  def vcxPayloadObject(json: JSONObject): JSONObject = {
    val rtn = Try {
      new JSONObject(
        json.getString("@msg")
      )
    }
    JSONObjectUtil.transformFailure(rtn)(new Exception("Unable to find vcxMessage", _))
      .get
  }

  def vcxPayloadArray(json: JSONObject): JSONArray = {
    val rtn = Try {
      new JSONArray(
        json.getString("@msg")
      )
    }
    JSONObjectUtil.transformFailure(rtn)(new Exception("Unable to find vcxMessage", _))
      .get
  }

  private def getStringElement(json: JSONObject, el: String): String = {
    val rtn =  Try {
      json.getString(el)
    }
    JSONObjectUtil.transformFailure(rtn)(new Exception(s"Unable to find '$el' in the message", _))
      .get
  }

  def vcxMessageReceiver(json:JSONObject): String = {
    getStringElement(json, "pairwiseDID")
  }

  def vcxMessageSender(json:JSONObject): String = {
    getStringElement(json, "senderDID")
  }

  def vcxMessageType(json:JSONObject): String = {
    getStringElement(json, "type")
  }

  def vcxMessageId(json:JSONObject): String = {
    getStringElement(json, "uid")
  }

  def vcxMessageDecryptedPayload(json:JSONObject): String = {
    getStringElement(json, "decryptedPayload")
  }
}
