package com.evernym.verity.http.base

import java.net.InetAddress

import akka.http.scaladsl.model.headers.`X-Real-Ip`
import akka.http.scaladsl.model._

import scala.collection.immutable

trait AgentReqBuilder {
  def buildReq(hm: HttpMethod, path: String, he: RequestEntity = HttpEntity.Empty, headers: Seq[HttpHeader] = Seq.empty): HttpRequest =  {
    val req = HttpRequest(
      method = hm,
      uri = path,
      entity = he,
      headers = headers.to[immutable.Seq]
    )
    req.addHeader(`X-Real-Ip`(RemoteAddress(InetAddress.getLocalHost)))
  }

  def buildPostReq(path: String, he: RequestEntity = HttpEntity.Empty, headers: Seq[HttpHeader] = Seq.empty): HttpRequest =
    buildReq(HttpMethods.POST, path, he, headers)

  def buildPutReq(path: String, he: RequestEntity = HttpEntity.Empty, headers: Seq[HttpHeader] = Seq.empty): HttpRequest =
    buildReq(HttpMethods.PUT, path, he, headers)

  def buildGetReq(path: String, headers: Seq[HttpHeader] = Seq.empty): HttpRequest =
    buildReq(HttpMethods.GET, path, headers=headers)

  def buildAgentPostReq(payload: Array[Byte]): HttpRequest =
    buildPostReq("/agency/msg", HttpEntity(MediaTypes.`application/octet-stream`, payload))

}
