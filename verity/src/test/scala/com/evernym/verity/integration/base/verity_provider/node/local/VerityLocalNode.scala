package com.evernym.verity.integration.base.verity_provider.node.local

import akka.actor.CoordinatedShutdown
import akka.cluster.{Cluster, MemberStatus}
import akka.cluster.MemberStatus.{Down, Removed, Up}
import akka.testkit.TestKit
import com.evernym.verity.actor.Platform
import com.evernym.verity.app_launcher.HttpServer
import com.evernym.verity.integration.base.verity_provider.PortProfile
import com.evernym.verity.integration.base.verity_provider.node.VerityNode
import com.evernym.verity.integration.base.verity_provider.node.local.LocalVerity.waitAtMost
import com.evernym.verity.observability.logs.LoggingUtil
import com.typesafe.config.{Config, ConfigMergeable}

import java.nio.file.Path
import com.evernym.verity.util2.ExecutionContextProvider
import com.typesafe.scalalogging.Logger
import org.scalatest.concurrent.ScalaFutures.convertScalaFuture

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._


case class VerityLocalNode(tmpDirPath: Path,
                           appSeed: String,
                           portProfile: PortProfile,
                           otherNodeArteryPorts: Seq[Int],
                           serviceParam: Option[ServiceParam],
                           overriddenConfig: Option[Config],
                           ecp: ExecutionContextProvider,
                           baseConfig: ConfigMergeable) extends VerityNode {

  var isAvailable: Boolean = false
  private var _httpServer: HttpServer = _

  def httpServer: HttpServer = _httpServer

  def platform: Platform = httpServer.platform

  private val logger: Logger = LoggingUtil.getLoggerByName("VerityLocalNode")

  def start()(implicit ec: ExecutionContext): Future[Unit] = {
    logger.info(s"[rg-00] start verity instance ${portProfile.artery}")
    if (!isAvailable) {
      Future {
        logger.info(s"[rg-01] Future class loader is ${Thread.currentThread().getContextClassLoader}")
        startVerityInstance()
      } map {
        srv =>
          _httpServer = srv
          isAvailable = true
          logger.info(s"[rg-00] verity instance ${portProfile.artery} started")
      }
    } else {
      Future.successful(())
    }
  }

  def stop()(implicit ec: ExecutionContext): Future[Unit] = {
    Future {
      stopGracefully()
    }
  }

  //is this really ungraceful shutdown?
  //  private def stopUngracefully(): Unit = {
  //    def stopHttpServer(): Unit = {
  //      val httpStopFut = httpServer.stop()
  //      Await.result(httpStopFut, 30.seconds)
  //    }
  //
  //    def stopActorSystem(): Unit = {
  //      val platformStopFut = platform.actorSystem.terminate()
  //      Await.result(platformStopFut, 30.seconds)
  //    }
  //
  //    isAvailable = false
  //    stopHttpServer()
  //    stopActorSystem()
  //  }

  private def stopGracefully(): Unit = {
    isAvailable = false
    //TODO: need to resolve this
    if (httpServer == null){
      logger.warn(s"[rg-00] stopGracefully called, but it seems like node was not be started, node ${portProfile.artery}")
      return
    }
    val cluster = Cluster(platform.actorSystem)

    val result = CoordinatedShutdown(platform.actorSystem).run(UserInitiatedShutdown)

    assert(result.isReadyWithin(20.seconds),"Coordinated shutdown failed")
    TestKit.awaitCond(isNodeShutdown(cluster), waitAtMost, 300.millis)
  }

  private def isNodeShutdown(cluster: Cluster): Boolean = {
    List(Removed, Down).contains(cluster.selfMember.status)
  }

  private def startVerityInstance(): HttpServer = {
    LocalVerity(baseConfig, verityNodeParam, ecp, bootstrapApp = false, trackMessageProgress = true)
  }

  /**
   * check if given node is up and
   * it's cluster state to conform with otherNodesStatus (if given)
   *
   * @param otherNodesStatus other nodes and their expected status
   * @return
   */
  def checkIfNodeIsUp(otherNodesStatus: Map[VerityNode, List[MemberStatus]] = Map.empty): Boolean = {
    require(!otherNodesStatus.keySet.map(_.portProfile.artery).contains(portProfile.artery),
      "otherNodesStatus should not contain current node")

    val cluster = Cluster(platform.actorSystem)
    cluster.selfMember.status == Up &&
      otherNodesStatus.forall { case (otherNode, expectedStatus) =>
        val otherMember = cluster.state.members.find(_.address.toString.contains(otherNode.portProfile.artery.toString))
        otherMember match {
          case None if expectedStatus.contains(Down) || expectedStatus.contains(Removed) => true
          case None => false
          case Some(om) => expectedStatus.contains(om.status)
        }
      }
  }

  //start()
}

case object UserInitiatedShutdown extends CoordinatedShutdown.Reason