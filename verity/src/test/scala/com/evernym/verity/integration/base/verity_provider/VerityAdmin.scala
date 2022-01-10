package com.evernym.verity.integration.base.verity_provider

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse}
import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.integration.base.verity_provider.node.local.LocalVerity.waitAtMost
import com.evernym.verity.ledger.LedgerTxnExecutor

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.{Duration, SECONDS}


object VerityAdmin {

  implicit val actorSystem: ActorSystem = ActorSystemVanilla("verity-admin")

  def bootstrapApplication(port: Int,
                           appSeed: String,
                           atMost: Duration = waitAtMost,
                           ledgerTxnExecutor: Option[LedgerTxnExecutor])(implicit executionContext: ExecutionContext): Unit = {
    if (appSeed.length != 32) throw new Exception("Seeds must be exactly 32 characters long")

    val keySetupResp = Await.result(
      Http().singleRequest(
        HttpRequest(
          method=HttpMethods.POST,
          uri = s"http://localhost:$port/agency/internal/setup/key",
          entity = HttpEntity(
            ContentTypes.`application/json`,
            s"""{"seed":"$appSeed"}"""
          )
        )
      )
      , atMost
    )

    checkAgencyKeySetup(keySetupResp)

    val endpointSetupResp = Await.result(
      Http().singleRequest(
        HttpRequest(
          method=HttpMethods.POST,
          uri = s"http://localhost:$port/agency/internal/setup/endpoint",
          entity = HttpEntity.Empty
        )
      )
      , atMost
    )

    checkAgencyEndpointSetup(endpointSetupResp)

  }

  private def checkAgencyKeySetup(httpResp: HttpResponse)(implicit executionContext: ExecutionContext): Unit = {
    val json = parseHttpResponse(httpResp)
    val apd = JacksonMsgCodec.fromJson[AgencyPublicDid](json)
    require(apd.DID.nonEmpty, "agency DID should not be empty")
    require(apd.verKey.nonEmpty, "agency verKey should not be empty")
  }

  private def checkAgencyEndpointSetup(httpResp: HttpResponse)(implicit executionContext: ExecutionContext): Unit = {
    val json = parseHttpResponse(httpResp)
    require(json == "OK", s"agency endpoint not updated: $json")
  }


  protected def parseHttpResponse(resp: HttpResponse)(implicit executionContext: ExecutionContext): String = {
    awaitFut(resp.entity.dataBytes.runReduce(_ ++ _).map(_.utf8String))
  }

  protected def awaitFut[T](fut: Future[T]): T = {
    Await.result(fut, Duration(20, SECONDS))
  }
}
