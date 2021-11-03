package com.evernym.verity.util

import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.typesafe.scalalogging.Logger
import org.apache.commons.net.util.SubnetUtils
import org.apache.http.conn.util.InetAddressUtils


object SubnetUtilsExt {

  val logger: Logger = getLoggerByClass(classOf[SubnetUtilsExt])

  def isIpAddressOrCidrNotation(token: String): Boolean = {
    isClassfulIpAddress(token) || isClasslessIpAddress(token)
  }

  def getSubnetUtilsExt(ipAddress: String): SubnetUtilsExt = {
    val classlessIpAddress = if (isClassfulIpAddress(ipAddress)) ipAddress + "/32" else ipAddress
    val utils = new SubnetUtilsExt(classlessIpAddress)
    logger.trace("ip address range: " + utils.getSubnetInfo.getLowAddress +
      " - " + utils.getSubnetInfo.getHighAddress + " for CIDR IP: "+ipAddress)
    utils
  }

  def isClassfulIpAddress(token: String): Boolean = InetAddressUtils.isIPv4Address(token)

  def isClasslessIpAddress(token: String): Boolean = {
    try {
      new SubnetUtilsExt(token)
      true
    } catch {
      case _ @ (_ :IllegalArgumentException | _ : NumberFormatException) => false
    }
  }

}

//TODO: set inclusive host is true, can make it configurable
class SubnetUtilsExt(cidrNotation: String) extends SubnetUtils(cidrNotation) {
  private val subnetInfo = getInfo

  setInclusiveHostCount(true)

  def isIpRangeConflicting(otherRange: SubnetUtilsExt): Boolean = {
    val range1High = subnetInfo.getHighAddress
    val range1Low = subnetInfo.getLowAddress

    val range2High = otherRange.subnetInfo.getHighAddress
    val range2Low = otherRange.subnetInfo.getLowAddress

    val isRange1ConflictsWithRange2 = subnetInfo.isInRange(range2Low) || subnetInfo.isInRange(range2High)
    val isRange2ConflictsWithRange1 = otherRange.subnetInfo.isInRange(range1Low) || otherRange.subnetInfo.isInRange(range1High)

    isRange1ConflictsWithRange2 || isRange2ConflictsWithRange1
  }

  def getSubnetInfo: SubnetUtils#SubnetInfo = subnetInfo
}
