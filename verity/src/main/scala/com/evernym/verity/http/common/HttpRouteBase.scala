package com.evernym.verity.http.common

import akka.actor.ActorSystem
import akka.http.scaladsl.model.MediaType.NotCompressible
import akka.http.scaladsl.model._
import com.evernym.verity.actor.persistence.HasActorResponseTimeout
import com.evernym.verity.config.AppConfig

import scala.concurrent.ExecutionContextExecutor


/**
 * common code (error handling, ip address checking etc) to be used by all other route handlers
 */
trait HttpRouteBase
  extends RouteSvc
    with MetricsSupport
    with AllowedIpsResolver
    with HasActorResponseTimeout {
}


object HttpCustomTypes {

  private val HTTP_MEDIA_TYPE_APPLICATION = "application"
  private val HTTP_MEDIA_SUB_TYPE_SSI_AGENT_WIRE = "ssi-agent-wire"
  private val HTTP_MEDIA_SUB_TYPE_DIDCOMM_ENVELOPE_ENC = "didcomm-envelope-enc"

  lazy val MEDIA_TYPE_SSI_AGENT_WIRE: MediaType.Binary = MediaType.customBinary(
    HTTP_MEDIA_TYPE_APPLICATION,
    HTTP_MEDIA_SUB_TYPE_SSI_AGENT_WIRE,
    NotCompressible)

  lazy val MEDIA_TYPE_DIDCOMM_ENVELOPE_ENC: MediaType.Binary = MediaType.customBinary(
    HTTP_MEDIA_TYPE_APPLICATION,
    HTTP_MEDIA_SUB_TYPE_DIDCOMM_ENVELOPE_ENC,
    NotCompressible)
}

trait HttpSvc {
  implicit def system: ActorSystem
}

trait ConfigSvc {
  def appConfig: AppConfig
}

trait RouteSvc extends HttpSvc with ConfigSvc {
  implicit def executor: ExecutionContextExecutor
}
