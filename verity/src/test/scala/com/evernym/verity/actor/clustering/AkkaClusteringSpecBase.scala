package com.evernym.verity.actor.clustering

import java.util.UUID

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import com.evernym.verity.Status.StatusDetail
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.testkit.actor.{ActorSystemConfig, MockAgentActorContext}
import com.evernym.verity.actor.{Platform, agentRegion}
import com.evernym.verity.testkit.{BasicSpec, CleansUpIndyClientFirst}
import com.evernym.verity.ActorErrorResp
import com.typesafe.config.{Config, ConfigFactory}

import scala.reflect.ClassTag


trait MultiNodeClusterSpecLike
  extends BasicSpec
    with ActorSystemConfig
    with CleansUpIndyClientFirst {

  lazy val firstNodePort: Int = getNextAvailablePort
  lazy val systemName: String = "actorSpecSystem" + firstNodePort
  lazy val seedNodeConfig: Option[Config] = generateSeedNodeConfig(systemName, firstNodePort)

  lazy val node1: TestNodePlatform = {
    val (as, config) = createNodeSystem(systemName, seedNodeConfig, Option(firstNodePort))
    new TestNodePlatform(as, config)
  }

  lazy val node2: TestNodePlatform = {
    val (as, config) = createNodeSystem(systemName, seedNodeConfig)
    new TestNodePlatform(as, config)
  }

  def generateSeedNodeConfig(systemName: String, firstNodePort: Int): Option[Config] = {
    Option {
      ConfigFactory parseString {
        s"""
          akka {
            cluster {
              seed-nodes = [
                "akka://$systemName@127.0.0.1:$firstNodePort"
              ]
            }
          }
        """
      }
    }
  }

  def createNodeSystem(systemName: String, overrideConfig: Option[Config]=None, port: Option[Int]=None): (ActorSystem, Config) = {
    val portToBeUsed = port.getOrElse(getNextAvailablePort)
    val config = getConfigByPort(portToBeUsed, overrideConfig)
    (ActorSystem(systemName, config), config)
  }

  lazy val forAllClient: List[TestClient] = List(node1, node2).map(_.client)
  lazy val node1Client: TestClient = node1.client
  lazy val node2Client: TestClient = node2.client

  class TestNodePlatform(as: ActorSystem, config: Config)
    extends Platform(new MockAgentActorContext(as, new TestAppConfig(Option(config)))) {
    lazy val agencyAgentEntityId: String = UUID.nameUUIDFromBytes("agency-DID".getBytes()).toString
    lazy val aa: agentRegion = agentRegion(agencyAgentEntityId, agencyAgentRegion)
    val client: TestClient = new TestClient(TestProbe(), this)
  }

  case class TestClient(probe: TestProbe, nodePlatform: TestNodePlatform) {
    def sendToAgencyAgent(msg: Any): Unit = {
      nodePlatform.aa.tell(msg, probe.ref)
    }
    def expectError(statusDetail: StatusDetail): Unit = {
      probe.expectMsgType[ActorErrorResp].statusCode shouldBe statusDetail.statusCode
    }

    def expectMsgType[T: ClassTag]: T = {
      probe.expectMsgType[T]
    }

  }
}


