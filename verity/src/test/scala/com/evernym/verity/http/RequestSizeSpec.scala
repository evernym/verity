package com.evernym.verity.http

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives.ignoreTrailingSlash
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.testkit.AkkaTestBasic
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.http.base.AgentReqBuilder
import com.evernym.verity.http.common.HttpRouteBase
import com.evernym.verity.http.route_handlers.PlatformServiceProvider
import com.evernym.verity.http.route_handlers.open.PackedMsgEndpointHandler
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.testkit.BasicSpec


class RequestSizeSpec
  extends BasicSpec
    with ScalatestRouteTest
    with ProvidesMockPlatform
    with HttpRouteBase
    with PlatformServiceProvider
    with PackedMsgEndpointHandler
    with AgentReqBuilder {

  def endpointRoutes: Route = ignoreTrailingSlash { packedMsgRoute }

  override def metricsWriter: MetricsWriter = platform.agentActorContext.metricsWriter

  val MAX_ALLOWED_PAYLOAD_SIZE = 17825792   //TODO: to be finalized

  s"when sent payload with max allowed size" - {
    "should not respond with 405" ignore {
      platform.msgTracerRegion.path.toString.isEmpty should not be true   //basically starting the msg tracer region
      val allowedSize = Array.range(0, MAX_ALLOWED_PAYLOAD_SIZE).map(_.toByte)
      val httpReq = buildAgentPostReq(allowedSize)
      httpReq ~> endpointRoutes ~> check {
        status shouldBe NotFound    //is this wrong status code for "agency agent not setup" scenario, may be we should fix it
      }
    }
  }

  s"when sent payload with more than max allowed size" - {
    "should respond with 405" ignore {
      platform.msgTracerRegion.path.toString.isEmpty should not be true   //basically starting the msg tracer region
      val allowedSize = Array.range(0, MAX_ALLOWED_PAYLOAD_SIZE + 1000).map(_.toByte)
      val httpReq = buildAgentPostReq(allowedSize)
      httpReq ~> endpointRoutes ~> check {
        status shouldBe MethodNotAllowed    //TODO: finalize this
      }
    }
  }

  override lazy val agentActorContext: AgentActorContext = platform.agentActorContext
  override protected def createActorSystem(): ActorSystem = AkkaTestBasic.system()
}
