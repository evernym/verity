package com.evernym.verity.actor.clustering

import com.evernym.verity.Status._
import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.actor.agent.agency.{CreateKey, GetLocalAgencyIdentity}
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog


class MultiNodeClusterSpec extends MultiNodeClusterSpecLike  {

  "Agency admin" - {

    "validate cluster" in {
      node1.appConfig.getConfigStringReq("akka.remote.artery.canonical.port") should not be
        node2.appConfig.getConfigStringReq("akka.remote.artery.canonical.port")
    }

    "when sent get agency identity before creating key" in {
      forAllClient.foreach { client =>
        client.sendToAgencyAgent(GetLocalAgencyIdentity())
        client.expectError(AGENT_NOT_YET_CREATED)
      }
    }

    "when sent create key" in {
      node1Client.sendToAgencyAgent(CreateKey(seed = Option("s" * 32)))
      node1Client.expectMsgType[AgencyPublicDid]
    }

    "when sent create key again" taggedAs (UNSAFE_IgnoreLog) in {
      node2Client.sendToAgencyAgent(CreateKey(seed = Option("s" * 32)))
      node2Client.expectError(FORBIDDEN)
    }

    "when sent get agency identity after creating key" in {
      forAllClient.foreach { client =>
        client.sendToAgencyAgent(GetLocalAgencyIdentity())
        client.expectMsgType[AgencyPublicDid]
      }
    }
  }

}

