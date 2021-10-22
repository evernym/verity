package com.evernym.integrationtests.e2e.util

object PortProvider {

  def portByVerityInstanceName(name: String, fromPort: Int = 1, toPort: Int = 65536): Int = {
    fromPort + (name.hashCode % (toPort - fromPort))
  }

}