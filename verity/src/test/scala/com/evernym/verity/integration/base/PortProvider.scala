package com.evernym.verity.integration.base

import java.util.UUID
import scala.util.Random

object PortProvider {

  //global singleton object for test to make sure it doesn't allocate
  // already allocated/used port to avoid "port already used" error.
  private var allocatedPorts: Seq[Int] = Seq.empty

  private def getRandomPort(baseNumber: Int): Int = {
    val random = new Random(UUID.randomUUID().toString.hashCode)
    baseNumber + random.nextInt(900) + random.nextInt(90) + random.nextInt(9)
  }

  def generateUnusedPort(baseNumber: Int): Int = synchronized {
    val randomPort = getRandomPort(baseNumber)
    if (allocatedPorts.contains(randomPort)) generateUnusedPort(baseNumber)
    else {
      allocatedPorts = allocatedPorts :+ randomPort
      randomPort
    }
  }
}
