package com.evernym.verity.testkit.util

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethod, HttpMethods, HttpRequest, HttpResponse, StatusCode}
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec

import java.util.UUID
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.{Duration, FiniteDuration, SECONDS}
import scala.reflect.ClassTag

object HttpUtil {

  val futureTimeout: FiniteDuration = Duration(25, SECONDS)


  def sendBinaryReqToUrl(payload: Array[Byte], url: String, method:HttpMethod = HttpMethods.POST)
                        (implicit ec: ExecutionContext): HttpResponse = {
    awaitFut(
      Http().singleRequest(
        HttpRequest(
          method=method,
          uri = url,
          entity = HttpEntity(
            ContentTypes.`application/octet-stream`,
            payload
          )
        )
      ).flatMap(_.toStrict(futureTimeout))
    )
  }

  def sendJsonReqToUrl(payload: String, url: String, method:HttpMethod = HttpMethods.POST)
                      (implicit ec: ExecutionContext): HttpResponse = {
    awaitFut(
      Http().singleRequest(
        HttpRequest(
          method=method,
          uri = url,
          entity = HttpEntity(
            ContentTypes.`application/json`,
            payload
          )
        )
      ).flatMap(_.toStrict(futureTimeout))
    )
  }

  def sendGET(url: String)(implicit ec: ExecutionContext): HttpResponse = {
    awaitFut(
      Http().singleRequest(
        HttpRequest(
          method=HttpMethods.GET,
          uri = url,
          entity = HttpEntity.Empty
        )
      ).flatMap(_.toStrict(futureTimeout))
    )
  }

  def checkOKResponse(resp: HttpResponse)
                     (implicit ec: ExecutionContext): HttpResponse = {
    checkResponse(resp, OK)
  }

  def checkResponse(resp: HttpResponse,
                    expected: StatusCode)
                   (implicit ec: ExecutionContext): HttpResponse = {
    require(resp.status.intValue() == expected.intValue, {
      val json = parseHttpResponseAsString(resp)  //to avoid
      s"http response '${resp.status}' was not equal to expected '${expected.value}': $json"
    })
    resp
  }

  def parseHttpResponseAs[T: ClassTag](resp: HttpResponse)
                                      (implicit ec: ExecutionContext): T = {
    val respString = parseHttpResponseAsString(resp)
    JacksonMsgCodec.fromJson[T](respString)
  }

  def parseHttpResponseAsString(resp: HttpResponse)
                               (implicit ec: ExecutionContext): String = {
    awaitFut(Unmarshal(resp.entity).to[String])
  }

  def awaitFut[T](fut: Future[T]): T = {
    Await.result(fut, Duration(25, SECONDS))
  }

  implicit val system: ActorSystem = ActorSystemVanilla(UUID.randomUUID().toString)
}
