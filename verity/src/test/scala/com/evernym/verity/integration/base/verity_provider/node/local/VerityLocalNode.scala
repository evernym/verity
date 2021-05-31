package com.evernym.verity.integration.base.verity_provider.node.local

import akka.actor.ActorRef
import akka.cluster.{Cluster, MemberStatus}
import akka.cluster.MemberStatus.{Down, Removed, Up}
import akka.testkit.TestKit
import com.evernym.verity.actor.Platform
import com.evernym.verity.actor.node_singleton.DrainNode
import com.evernym.verity.app_launcher.HttpServer
import com.evernym.verity.integration.base.verity_provider.PortProfile
import com.evernym.verity.integration.base.verity_provider.node.VerityNode
import com.evernym.verity.integration.base.verity_provider.node.local.LocalVerity.atMost
import com.typesafe.config.Config

import java.nio.file.Path
import scala.concurrent.Await
import scala.concurrent.duration._


case class VerityLocalNode(tmpDirPath: Path,
                           appSeed: String,
                           portProfile: PortProfile,
                           otherNodeArteryPorts: Seq[Int],
                           serviceParam: Option[ServiceParam],
                           overriddenConfig: Option[Config]) extends VerityNode {

  var isAvailable: Boolean = false
  private var _httpServer: HttpServer = _

  def httpServer: HttpServer = _httpServer
  def platform: Platform = httpServer.platform

  def start(): Unit = {
    if (! isAvailable) {
      _httpServer = startVerityInstance()
      isAvailable = true
    }
  }

  def stop(): Unit = {
    //TODO: at this stage, sometimes actor system logs 'java.lang.IllegalStateException: Pool shutdown unexpectedly' exception,
    // it doesn't impact the test in any way but should try to find and fix the root cause
    stopUngracefully()
  }

  //is this really ungraceful shutdown?
  private def stopUngracefully(): Unit = {
    isAvailable = false
    stopHttpServer()
    stopActorSystem()
  }

  private def stopHttpServer(): Unit = {
    val httpStopFut = httpServer.stop()
    Await.result(httpStopFut, 30.seconds)
  }

  private def stopActorSystem(): Unit = {
    val platformStopFut = platform.actorSystem.terminate()
    Await.result(platformStopFut, 30.seconds)
  }

  private def stopGracefully(): Unit = {
    //TODO: to find out why this one fails intermittently
    isAvailable = false
    val cluster = Cluster(platform.actorSystem)
    platform.nodeSingleton.tell(DrainNode, ActorRef.noSender)
    TestKit.awaitCond(isNodeShutdown(cluster), atMost, 3.seconds)
  }

  private def isNodeShutdown(cluster: Cluster): Boolean = {
    List(Removed, Down).contains(cluster.selfMember.status)
  }

  private def startVerityInstance(): HttpServer = {
    val httpServer = LocalVerity(verityNodeParam, bootstrapApp = false)
    httpServer
  }

  /**
   * check if given node is up and
   * it's cluster state to conform with otherNodesStatus (if given)
   *
   * @param otherNodesStatus other nodes and their expected status
   * @return
   */
  def checkIfNodeIsUp(otherNodesStatus: Map[VerityNode, List[MemberStatus]] = Map.empty): Boolean = {
    require(! otherNodesStatus.keySet.map(_.portProfile.artery).contains(portProfile.artery),
      "otherNodesStatus should not contain current node")

    val cluster = Cluster(platform.actorSystem)
    cluster.selfMember.status == Up &&
      otherNodesStatus.forall { case (otherNode, expectedStatus) =>
        val otherMember = cluster.state.members.find(_.address.toString.contains(otherNode.portProfile.artery.toString))
        otherMember match {
          case None if expectedStatus.contains(Down) || expectedStatus.contains(Removed) => true
          case None     => false
          case Some(om) => expectedStatus.contains(om.status)
        }
      }
  }

  start()
}