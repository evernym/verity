package com.evernym.verity.actor.clustering

import com.evernym.verity.util2.Status._
import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.actor.agent.agency.{CreateKey, GetLocalAgencyIdentity}
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog


class BasicMultiNodeClusterSpec extends MultiNodeClusterSpecLike  {

  //by default 'MultiNodeClusterSpecLike' will create two node cluster
  //unless 'numberOfNodes' is overridden by this implementing class
  lazy val node1: NodePlatform = allNodes.head
  lazy val node2: NodePlatform = allNodes.last
  lazy val node1Client: NodeClient = node1.client
  lazy val node2Client: NodeClient = node2.client

  "Agency admin" - {

    "validates cluster configuration" in {
      //this confirms that both the nodes are running on different ports
      node1.appConfig.getStringReq("akka.remote.artery.canonical.port") should not be
        node2.appConfig.getStringReq("akka.remote.artery.canonical.port")
    }

    "when sent get agency identity before creating key" in {
      allNodeClients.foreach { nodeClient =>
        nodeClient.sendToAgencyAgent(GetLocalAgencyIdentity())
        nodeClient.expectError(AGENT_NOT_YET_CREATED)
      }
    }

    "when sent create key" in {
      node1Client.sendToAgencyAgent(CreateKey(seed = Option("s" * 32)))
      node1Client.expectMsgType[AgencyPublicDid]
    }

    "when sent create key again" taggedAs UNSAFE_IgnoreLog in {
      node2Client.sendToAgencyAgent(CreateKey(seed = Option("s" * 32)))
      node2Client.expectError(FORBIDDEN)
    }

    "when sent get agency identity after creating key" in {
      allNodeClients.foreach { client =>
        client.sendToAgencyAgent(GetLocalAgencyIdentity())
        client.expectMsgType[AgencyPublicDid]
      }
    }
  }

}

