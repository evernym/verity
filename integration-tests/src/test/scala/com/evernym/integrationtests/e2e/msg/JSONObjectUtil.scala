package com.evernym.integrationtests.e2e.msg

import org.json.JSONObject

import scala.util.{Failure, Try}

object JSONObjectUtil {
  def transformFailure[U](t: Try[U])(newThrowable: Throwable => Throwable): Try[U] = {
    t match {
      case Failure(oldThrowable) => Failure(newThrowable(oldThrowable))
      case s => s
    }
  }

  def threadId(json: JSONObject): String = {
    val rtn = Try {
      json.getJSONObject("~thread").getString("thid")
    }
    transformFailure(rtn)(new Exception("Unable to find threadId", _))
      .get
  }

}
