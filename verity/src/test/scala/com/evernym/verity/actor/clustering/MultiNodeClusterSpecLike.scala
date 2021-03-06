package com.evernym.verity.actor.clustering

import java.util.UUID

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import com.evernym.verity.Status.StatusDetail
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.testkit.actor.{ActorSystemConfig, MockAgentActorContext, MockPlatformServices}
import com.evernym.verity.actor.{Platform, agentRegion}
import com.evernym.verity.testkit.{BasicSpec, CleansUpIndyClientFirst}
import com.evernym.verity.ActorErrorResp
import com.typesafe.config.{Config, ConfigFactory}

import scala.reflect.ClassTag


/**
 * a base class to test multi node cluster
 * implementing spec just needs to extend this trait and
 * optionally override 'numberOfNodes' (by default it will generate 2 node cluster)
 */
trait MultiNodeClusterSpecLike
  extends BasicSpec
    with ActorSystemConfig
    with CleansUpIndyClientFirst {

  //can be overridden by implementing class
  def numberOfNodes: Int = 2

  lazy val firstNodePort: Int = getNextAvailablePort
  lazy val systemName: String = "actorSpecSystem" + firstNodePort
  lazy val seedNodeConfig: Option[Config] = generateSeedNodeConfig(systemName, firstNodePort)

  lazy val allNodes: List[NodePlatform] = {
    (1 to numberOfNodes).map { i =>
      val (as, config) = if (i == 1) {
        createNodeSystem(systemName, seedNodeConfig, Option(firstNodePort))
      } else {
        createNodeSystem(systemName, seedNodeConfig)
      }
      new NodePlatform(as, config)
    }
  }.toList

  lazy val allNodeClients: List[NodeClient] = allNodes.map(_.client)

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

  /**
   * represents a node (an actor system)
   * each node also has corresponding 'client' as well to communicate (send and receive messages)
   * to corresponding node
   * @param as
   * @param config
   */
  class NodePlatform(as: ActorSystem, config: Config)
    extends Platform(new MockAgentActorContext(as, new TestAppConfig(Option(config))), MockPlatformServices) {
    lazy val agencyAgentEntityId: String = UUID.nameUUIDFromBytes("agency-DID".getBytes()).toString
    lazy val aa: agentRegion = agentRegion(agencyAgentEntityId, agencyAgentRegion)
    val client: NodeClient = NodeClient(TestProbe(), this)
  }

  case class NodeClient(probe: TestProbe, nodePlatform: NodePlatform) {
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


