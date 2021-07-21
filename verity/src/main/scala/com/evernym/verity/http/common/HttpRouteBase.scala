package com.evernym.verity.http.common

import java.net.{Inet4Address, InetAddress, NetworkInterface}
import java.util.{Enumeration => JavaEnumeration}

import akka.actor.ActorSystem
import akka.http.scaladsl.model.MediaType.NotCompressible
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Directive1
import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status.FORBIDDEN
import com.evernym.verity.actor.persistence.HasActorResponseTimeout
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.config.ConfigConstants.INTERNAL_API_ALLOWED_FROM_IP_ADDRESSES
import com.evernym.verity.config.AppConfig
import com.evernym.verity.metrics.CustomMetrics.{AS_ENDPOINT_HTTP_AGENT_MSG_COUNT, AS_ENDPOINT_HTTP_AGENT_MSG_FAILED_COUNT, AS_ENDPOINT_HTTP_AGENT_MSG_SUCCEED_COUNT}
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.util.SubnetUtilsExt
import com.typesafe.scalalogging.Logger

import scala.concurrent.ExecutionContextExecutor
import scala.reflect.ClassTag


/**
 * common code (error handling, ip address checking etc) to be used by all other route handlers
 */
trait HttpRouteBase
  extends RouteSvc
   with HasActorResponseTimeout {

  def logger: Logger
  def appConfig: AppConfig

  protected lazy val internalApiAllowedFromIpAddresses: List[SubnetUtilsExt] = {
    var allowedIPs: List[String] = appConfig.getStringListReq(INTERNAL_API_ALLOWED_FROM_IP_ADDRESSES)
    if (allowedIPs.isEmpty) {
      val interfaces: JavaEnumeration[NetworkInterface] = NetworkInterface.getNetworkInterfaces
      while(interfaces.hasMoreElements)
      {
        val interface = interfaces.nextElement match {
          case interface: NetworkInterface => interface
          case _ => ???
        }
        val inetAddresses: JavaEnumeration[InetAddress] = interface.getInetAddresses
        while (inetAddresses.hasMoreElements) {
          inetAddresses.nextElement match {
            case inetAddress: InetAddress =>
              if((inetAddress.isSiteLocalAddress || inetAddress.isLoopbackAddress)
                && inetAddress.isInstanceOf[Inet4Address]) {
                val ip = inetAddress.toString.replace("/", "").concat("/32")
                logger.debug(s"Adding site local address ${ip} to list of trusted IPs that can call" +
                  s" the internal API endpoints")
                allowedIPs = allowedIPs :+ ip
              }
            case _ => ???
          }
        }
        logger.info(INTERNAL_API_ALLOWED_FROM_IP_ADDRESSES + " undefined/empty. Defaulting to the current list of " +
          "site local (10 dot, 127 dot, and 172 dot) network interface addresses")
        logger.debug(INTERNAL_API_ALLOWED_FROM_IP_ADDRESSES + s" is now ${allowedIPs.mkString(", ")}")
        allowedIPs.map(ip => new SubnetUtilsExt(ip))
      }
    }
    allowedIPs.map(ip => new SubnetUtilsExt(ip))
  }

  def optionalEntityAs[T: ClassTag]: Directive1[Option[T]] = {
    extractRequest.flatMap { req =>
      if (req.entity.contentLengthOption.contains(0L)) {
        provide(Option.empty[T])
      } else {
        entity(as[String]).flatMap { e =>
          val nativeMsg = DefaultMsgCodec.fromJson(e)
          provide(Some(nativeMsg))
        }
      }
    }
  }

  def entityAs[T: ClassTag]: Directive1[T] = {
    optionalEntityAs[T].map(_.getOrElse(throw new RuntimeException("entity not found")))
  }

  protected def checkIfApiCalledFromAllowedIPAddresses(callerIpAddress: String, allowedIpAddresses: List[SubnetUtilsExt])
                                            (implicit req: HttpRequest): Unit = {
    val allowedSubnetOpt = allowedIpAddresses.find { ipsn =>
      ipsn.getInfo.isInRange(callerIpAddress)
    }
    allowedSubnetOpt match {
      case Some(allowedIp: SubnetUtilsExt) =>
        logger.debug(
          s"'$callerIpAddress' matches allowed ip address rule (${allowedIp.getInfo.getCidrSignature}): ${req.getUri}",
          ("caller_ip", callerIpAddress), ("req_uri", req.getUri))
      case None =>
        logger.warn(
          s"'$callerIpAddress' does NOT match any configured/allowed ip addresses: ${req.getUri}",
          ("caller_ip", callerIpAddress), ("req_uri", req.getUri))
        logger.debug(s"Configured/Allowed ip addresses: " + internalApiAllowedFromIpAddresses.mkString(", "))
        throw new BadRequestErrorException(FORBIDDEN.statusCode)
    }
  }

  def checkIfInternalApiCalledFromAllowedIPAddresses(callerIpAddress: String)
                                                    (implicit req: HttpRequest): Unit = {
    checkIfApiCalledFromAllowedIPAddresses(callerIpAddress, internalApiAllowedFromIpAddresses)
  }

  def clientIpAddress(implicit remoteAddress: RemoteAddress): String =
    remoteAddress.getAddress().get.getHostAddress

  def incrementAgentMsgCount: Unit = MetricsWriter.gaugeApi.increment(AS_ENDPOINT_HTTP_AGENT_MSG_COUNT)
  def incrementAgentMsgSucceedCount: Unit = MetricsWriter.gaugeApi.increment(AS_ENDPOINT_HTTP_AGENT_MSG_SUCCEED_COUNT)
  def incrementAgentMsgFailedCount(tags: Map[String, String] = Map.empty): Unit =
    MetricsWriter.gaugeApi.incrementWithTags(AS_ENDPOINT_HTTP_AGENT_MSG_FAILED_COUNT, tags)
}


object HttpCustomTypes {

  private val HTTP_MEDIA_TYPE_APPLICATION = "application"
  private val HTTP_MEDIA_SUB_TYPE_SSI_AGENT_WIRE = "ssi-agent-wire"

  lazy val MEDIA_TYPE_SSI_AGENT_WIRE = MediaType.customBinary(
    HTTP_MEDIA_TYPE_APPLICATION,
    HTTP_MEDIA_SUB_TYPE_SSI_AGENT_WIRE,
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
