package com.evernym.verity.testkit.util

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse}
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.{Duration, SECONDS}
import scala.reflect.ClassTag

object HttpUtil {

  def sendBinaryReqToUrl(payload: Array[Byte], url: String): HttpResponse = {
    awaitFut(
      Http().singleRequest(
        HttpRequest(
          method=HttpMethods.POST,
          uri = url,
          entity = HttpEntity(
            ContentTypes.`application/octet-stream`,
            payload
          )
        )
      )
    )
  }

  def sendJsonReqToUrl(payload: String, url: String): HttpResponse = {
    awaitFut(
      Http().singleRequest(
        HttpRequest(
          method=HttpMethods.POST,
          uri = url,
          entity = HttpEntity(
            ContentTypes.`application/json`,
            payload
          )
        )
      )
    )
  }

  def sendGET(url: String): HttpResponse = {
    awaitFut(
      Http().singleRequest(
        HttpRequest(
          method=HttpMethods.GET,
          uri = url,
          entity = HttpEntity.Empty
        )
      )
    )
  }

  def parseHttpResponseAs[T: ClassTag](resp: HttpResponse)
                                      (implicit futExecutionContext: ExecutionContext): T = {
    val respString = parseHttpResponseAsString(resp)
    JacksonMsgCodec.fromJson[T](respString)
  }

  def parseHttpResponseAsString(resp: HttpResponse)
                               (implicit futExecutionContext: ExecutionContext): String = {
    awaitFut(resp.entity.dataBytes.runReduce(_ ++ _).map(_.utf8String))
  }

  protected def awaitFut[T](fut: Future[T]): T = {
    Await.result(fut, Duration(25, SECONDS))
  }

  implicit val actorSystem: ActorSystem = ActorSystemVanilla("http")
}
