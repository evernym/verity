package com.evernym.verity.actor.testkit.actor

import akka.actor.ActorSystem
import com.evernym.verity.integration.base.PortProvider
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}

import java.io.InputStream
import java.net.URL
import java.util
import java.util.Collections
import scala.jdk.CollectionConverters._

private class ClassLoaderWrapper(wrapped: ClassLoader) extends ClassLoader {
  //  override def getName: String = wrapped.getName

  override def loadClass(name: String): Class[_] = wrapped.loadClass(name)

  override def getResource(name: String): URL = wrapped.getResource(name)

  override def getResources(name: String): util.Enumeration[URL] = wrapped.getResources(name)

  //  override def resources(name: String): stream.Stream[URL] = wrapped.resources(name)

  override def getResourceAsStream(name: String): InputStream = wrapped.getResourceAsStream(name)

  override def setDefaultAssertionStatus(enabled: Boolean): Unit = wrapped.setDefaultAssertionStatus(enabled)

  override def setPackageAssertionStatus(packageName: String, enabled: Boolean): Unit = wrapped.setPackageAssertionStatus(packageName, enabled)

  override def setClassAssertionStatus(className: String, enabled: Boolean): Unit = wrapped.setClassAssertionStatus(className, enabled)

  override def clearAssertionStatus(): Unit = wrapped.clearAssertionStatus()
}

private class FilterLocalReferenceConfClassLoader(wrapped: ClassLoader) extends ClassLoaderWrapper(wrapped) {
  override def getResources(name: String): util.Enumeration[URL] = {
    if(name.endsWith("reference.conf")) {
      Collections.enumeration(
        super.getResources(name)
          .asScala
          .toSeq
          .filterNot(
            _.getProtocol.startsWith("file")
          )
          .asJavaCollection
      )
    }
    else {
      super.getResources(name)
    }
  }
}

/**
 * Create an actor system with out local reference.conf config changes. This allow to start a Vanilla system
 * without application specific configurations (like clustering). Intended for use with test that want to use
 * akka abilities (like Akka HTTP client).
 */
object ActorSystemVanilla {


  def apply(name: String): ActorSystem = {
    apply(
      name,
      ConfigFactory.empty(), // Creates an ActorSystem unencumbered with Verity Config
      seedNodesWithRandomPorts = true
    )
  }

  def apply(name: String, config: Config): ActorSystem = {
    apply(
      name,
      config,
      seedNodesWithRandomPorts = true
    )
  }


  def apply(name: String, config: Config, seedNodesWithRandomPorts: Boolean): ActorSystem = {
    val configToBeUsed = if (seedNodesWithRandomPorts) seedNodesWithRandomPort(config) else config
    ActorSystem(
      name,
      configToBeUsed,
      new FilterLocalReferenceConfClassLoader(Thread.currentThread().getContextClassLoader) // remove verity reference.conf
    )
  }

  //this is to avoid port binding error (Address already in use)
  private def seedNodesWithRandomPort(config: Config): Config = {
    val existingSeedNodes =
      if (config.hasPath("akka.cluster.seed-nodes")) config.getStringList("akka.cluster.seed-nodes").asScala
      else List("akka://$systemName@127.0.0.1:25520")

    val updatedSeedNodes = existingSeedNodes
      .map{ url =>
        val lastIndex = url.lastIndexOf(":")
        val (prePort, port) = url.splitAt(lastIndex)
        prePort + ":" + PortProvider.getFreePort
      }

    config
      .withValue(
        "akka.cluster.seed-nodes",
        ConfigValueFactory.fromIterable(updatedSeedNodes.asJava)
      )
  }
}
