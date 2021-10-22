package com.evernym.integrationtests.e2e.util

import scala.collection.mutable

object PortProvider {

  val reservedPorts: mutable.Set[Int] = mutable.Set.empty

  def portByVerityInstanceName(name: String, fromPort: Int = 1, toPort: Int = 65536): Int = {
    fromPort + (name.hashCode % (toPort - fromPort))
  }

  def firstFreePort(fromPort: Int = 1, toPort: Int = 65536): Option[Int] = {
    (fromPort to toPort).find(!reservedPorts.contains(_)).map(p =>{
      reservedPorts.add(p)
      p
    })
  }

}