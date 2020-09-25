package com.evernym.verity.integration.veritysdk

import java.io.IOException

import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.ByteArrayEntity
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.util.EntityUtils

object TempSender {


  /*
  Clone of SDK sender that returns a string. We have work flows that are synchronous but we want the verity SDK
  to be asynchronous. These synchronous work flows are need for now to test protocols but we don't want to pollute the
  SDK interface. So we have this sender for now that should go away in time.
   */
  def sendMessage(url: String, message: Array[Byte]): String = {
    val verityMsgUrl = s"$url/agency/msg"
    val request = new HttpPost(verityMsgUrl)
    request.setEntity(new ByteArrayEntity(message))
    request.setHeader("Content-Type", "application/octet-stream")
    val response = HttpClientBuilder.create().build().execute(request)
    val statusCode = response.getStatusLine.getStatusCode
    if (statusCode > 399) {
      System.out.println("statusCode: " + statusCode)
      throw new IOException("Request failed! - " + EntityUtils.toString(response.getEntity))
    }
    else EntityUtils.toString(response.getEntity)
  }

}