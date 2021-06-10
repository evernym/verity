package com.evernym.verity.integration.base

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.util.Random

object PortProvider {

  //global singleton object for test to make sure it doesn't allocate
  // already allocated/used port to avoid "port already used" error.
  private val allocatedPorts: AtomicReference[Seq[Int]] = new AtomicReference(Seq.empty)

  private val tryAttempts = 15

  private def getRandomPort(baseNumber: Int): Int = {
    val random = new Random(UUID.randomUUID().toString.hashCode)
    baseNumber + random.nextInt(900) + random.nextInt(90) + random.nextInt(9)
  }

  def getUnusedPort(baseNumber: Int): Int = {
    val randomPorts = Stream.continually(getRandomPort(baseNumber)).take(tryAttempts)
    val availablePort = randomPorts find { port ⇒
      ! allocatedPorts.get().contains(port)
    } getOrElse sys.error(s"could not create unused port after $tryAttempts attempts")
    allocatedPorts.set(allocatedPorts.get() :+ availablePort)
    availablePort
  }
}
