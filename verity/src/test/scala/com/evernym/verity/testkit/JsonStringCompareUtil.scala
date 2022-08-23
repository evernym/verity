package com.evernym.verity.testkit

import org.json.JSONObject

object JsonStringCompareUtil
  extends Matchers {

  def shouldBeEqual(str1: String, str2: String): Unit = {
    val json1 = new JSONObject(str1)
    val json2 = new JSONObject(str2)
    json1.toString shouldBe json2.toString
  }
}
