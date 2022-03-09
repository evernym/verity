package com.evernym.verity.actor.testkit.actor

import akka.actor.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}

import java.io.InputStream
import java.net.URL
import java.util
import java.util.Collections
import scala.collection.JavaConverters._

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
    ActorSystem(
      name,
      ConfigFactory.empty(), // Creates an ActorSystem unencumbered with Verity Config
      new FilterLocalReferenceConfClassLoader(Thread.currentThread().getContextClassLoader) // remove verity reference.conf
    )
  }

  def apply(name: String, config: Config): ActorSystem = {
    ActorSystem(
      name,
      config,
      new FilterLocalReferenceConfClassLoader(Thread.currentThread().getContextClassLoader) // remove verity reference.conf
    )
  }

}
