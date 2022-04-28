package com.evernym.verity.http.common

import akka.http.scaladsl.model.{RemoteAddress, Uri}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants.INTERNAL_API_ALLOWED_FROM_IP_ADDRESSES
import com.evernym.verity.util.SubnetUtilsExt
import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status.FORBIDDEN
import com.typesafe.scalalogging.Logger

import java.net.{Inet4Address, NetworkInterface}
import java.util.{Enumeration => JavaEnumeration}

trait AllowedIpsResolver {

  def logger: Logger

  def appConfig: AppConfig

  protected lazy val allowedIpAddresses: List[SubnetUtilsExt] = {
    val configured: List[String] = appConfig.getStringListReq(INTERNAL_API_ALLOWED_FROM_IP_ADDRESSES)
    val allowedIPs = if (configured.nonEmpty) configured else computeAddresses()
    allowedIPs.map(ip => new SubnetUtilsExt(ip))
  }

  private def computeAddresses(): List[String] = {
    import scala.jdk.CollectionConverters._

    val interfaces: JavaEnumeration[NetworkInterface] = NetworkInterface.getNetworkInterfaces
    val addresses = interfaces.asScala.flatMap(_.getInetAddresses.asScala)
      .filter(_.isInstanceOf[Inet4Address])
      .filter(address => address.isSiteLocalAddress || address.isLoopbackAddress)
      .toSeq

    val ips = addresses.map(_.toString.replace("/", "").concat("/32"))
    logIps(ips)

    ips.toList
  }

  private def logIps(ips: Seq[String]): Unit = {
    ips.foreach { ip =>
      logger.debug(s"Adding site local address ${ip} to list of trusted IPs that can call" +
        s" the internal API endpoints")
    }

    logger.info(INTERNAL_API_ALLOWED_FROM_IP_ADDRESSES + " undefined/empty. Defaulting to the current list of " +
      "site local (10 dot, 127 dot, and 172 dot) network interface addresses")
    logger.debug(INTERNAL_API_ALLOWED_FROM_IP_ADDRESSES + s" is now ${ips.mkString(", ")}")
  }

  def checkIfAddressAllowed(remoteAddress: RemoteAddress, uri: Uri): Unit = {
    val ip = remoteAddress.getAddress().get.getHostAddress
    val allowedSubnetOpt = allowedIpAddresses.find { ipsn =>
      ipsn.getInfo.isInRange(ip)
    }
    allowedSubnetOpt match {
      case Some(allowedIp: SubnetUtilsExt) =>
        logger.debug(s"'$ip' matches allowed ip address rule (${allowedIp.getInfo.getCidrSignature}): $uri", ("caller_ip", ip), ("req_uri", uri))
      case None =>
        logger.warn(s"'$ip' does NOT match any configured/allowed ip addresses: $uri", ("caller_ip", ip), ("req_uri", uri))
        logger.debug(s"Configured/Allowed ip addresses: ${allowedIpAddresses.mkString(", ")}")
        throw new BadRequestErrorException(FORBIDDEN.statusCode)
    }
  }

  // TODO: remove after implicits refactoring/improving
  def clientIpAddress(implicit remoteAddress: RemoteAddress): String =
    remoteAddress.getAddress().get.getHostAddress
}
