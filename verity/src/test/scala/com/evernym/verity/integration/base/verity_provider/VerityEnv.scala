package com.evernym.verity.integration.base.verity_provider

import akka.cluster.MemberStatus
import akka.cluster.MemberStatus.{Down, Removed, Up}
import akka.testkit.TestKit
import com.evernym.verity.actor.persistence.recovery.base.PersistentStoreTestKit
import com.evernym.verity.integration.base.PortProvider
import com.evernym.verity.integration.base.verity_provider.node.VerityNode
import com.evernym.verity.integration.base.verity_provider.node.local.LocalVerity.waitAtMost
import com.evernym.verity.integration.base.verity_provider.node.local.VerityLocalNode
import com.evernym.verity.testkit.mock.blob_store.MockBlobStore
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._


case class VerityEnv(seed: String,
                     nodes: Seq[VerityNode],
                     executionContext: ExecutionContext)
  extends Eventually
    with Matchers {
  implicit lazy val ec: ExecutionContext = executionContext

  var isVerityBootstrapped: Boolean = false

  def availableNodes: Seq[VerityNode] = nodes.filter(_.isAvailable)

  def stopNodeAtIndex(index: Int): Unit = {
    stopNodeAtIndexes(List(index))
  }

  def stopNodeAtIndexes(indexes: List[Int]): Unit = {
    indexes.foreach(checkIndex)

    val (targetNodes, remainingNodes) = {
      val (targetNodes, otherNodes) =
        nodes.zipWithIndex.partition { case (_, index) => indexes.contains(index) }
      (targetNodes.map(_._1), otherNodes.filter(_._1.isAvailable).map(_._1))
    }

    val future = Future.sequence(targetNodes.map(_.stop()))
    Await.result(future, VerityEnv.STOP_MAX_TIMEOUT)

    val nodesToBeChecked = remainingNodes.map { curNode =>
      val excludeArteryPorts = (targetNodes :+ curNode).map(_.portProfile.artery)
      val otherNodes = remainingNodes.filter { n => ! excludeArteryPorts.contains(n.portProfile.artery)}
      val otherNodeStatus: Map[VerityNode, List[MemberStatus]] =
        otherNodes.map(_ -> List(Up)).toMap ++ targetNodes.map(_ -> List(Removed, Down)).toMap
      (curNode, otherNodeStatus)
    }

    TestKit.awaitCond(nodesToBeChecked.forall(n => n._1.checkIfNodeIsUp(n._2)), waitAtMost, 200.millis)
  }

  /**
   * checks if given nodes are up and other node's status is also up for each of them
   *
   * @param targetNodes
   * @return
   */
  def checkIfNodesAreUp(targetNodes: Seq[VerityNode] = nodes): Boolean = {
    targetNodes.forall { tNode =>
      val otherNodesStatus = nodes
        .filterNot(_.portProfile.http == tNode.portProfile.http)
        .map(_ -> List(Up)).toMap
      tNode.checkIfNodeIsUp(otherNodesStatus)
    }
  }

  private def checkIndex(index: Int): Unit = {
    require(index >=0 && index < nodes.size, s"invalid index: $index")
  }

  def startNodeAtIndex(index: Int): Unit = {
    Await.result(nodes(index).start(), VerityEnv.START_MAX_TIMEOUT)
  }

  def restartNodeAtIndex(index: Int): Unit = {
    Await.result(nodes(index).restart(),  VerityEnv.MAX_RESTART_TIMEOUT)
  }

  def stopAllNodes(): Unit = {
    val future = Future.sequence(nodes.map(_.stop()))
    Await.result(future, VerityEnv.STOP_MAX_TIMEOUT)
  }

  def restartAllNodes(maxTimeout: FiniteDuration = VerityEnv.MAX_RESTART_TIMEOUT): Unit = {
    val future = Future.sequence(nodes.map(_.restart()))
    Await.result(future, maxTimeout)
  }

  def checkBlobObjectCount(keyStartsWith: String, expectedCount: Int, bucketName: String = "local-blob-store"): Unit = {
    eventually(timeout(Span(15, Seconds)), interval(Span(100, Millis))) {
      mockBlobStore.getBlobObjectCount(keyStartsWith, bucketName) shouldBe expectedCount
    }
  }

  lazy val mockBlobStore: MockBlobStore =
    nodes.head.serviceParam.flatMap(_.storageAPI).map(_.asInstanceOf[MockBlobStore]).getOrElse(
      throw new RuntimeException("mock blob store not set")
    )

  def init(): Unit = {
    if (! isVerityBootstrapped) {
      nodes.headOption.foreach { node =>
        VerityAdmin.bootstrapApplication(node.portProfile.http, node.appSeed, waitAtMost, node.serviceParam.flatMap(_.ledgerTxnExecutor))
        isVerityBootstrapped = true
      }
    }
  }

  init()

  lazy val endpointProvider: VerityEnvUrlProvider = VerityEnvUrlProvider(nodes)

  lazy val persStoreTestKit = new PersistentStoreTestKit(nodes.head.asInstanceOf[VerityLocalNode].platform.actorSystem, ec)
}

object VerityEnv {
  val START_MAX_TIMEOUT: FiniteDuration = 25.seconds
  val STOP_MAX_TIMEOUT: FiniteDuration = 45.seconds
  val MAX_RESTART_TIMEOUT: FiniteDuration = START_MAX_TIMEOUT + STOP_MAX_TIMEOUT
}

case class VerityEnvUrlProvider(private val _nodes: Seq[VerityNode]) {
  def availableNodeUrls: Seq[String] = {
    _nodes.filter(_.isAvailable).map { np =>
      s"http://localhost:${np.portProfile.http}"
    }
  }
}

object PortProfile {
  def random(): PortProfile = {
    val arteryPort      = PortProvider.getFreePort
    val akkaMgmtPort    = PortProvider.getFreePort
    val httpPort        = PortProvider.getFreePort
    val prometheusPort  = PortProvider.getFreePort
    PortProfile(httpPort, arteryPort, akkaMgmtPort, prometheusPort)
  }
}

case class PortProfile(http: Int, artery: Int, akkaManagement: Int, prometheusPort: Int) {
  def ports: Seq[Int] = Seq(http, artery, akkaManagement, prometheusPort)
}