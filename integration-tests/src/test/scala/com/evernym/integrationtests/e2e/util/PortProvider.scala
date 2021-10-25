package com.evernym.integrationtests.e2e.util

import scala.collection.mutable

object PortProvider {

  val reservedPorts: mutable.Set[Int] = mutable.Set.empty

  def portByVerityInstanceName(name: String, fromPort: Int, toPort: Int): Int = {
    require(toPort > fromPort, s"PortProvider error: fromPort '$fromPort' must be less than toPort '$toPort'")
    fromPort + (name.hashCode % (toPort - fromPort))
  }

  def firstFreePort(fromPort: Int, toPort: Int): Option[Int] = {
    require(toPort > fromPort, s"PortProvider error: fromPort '$fromPort' must be less than toPort '$toPort'")
    (fromPort to toPort).find(!reservedPorts.contains(_)).map(p =>{
      reservedPorts.add(p)
      p
    })
  }

}