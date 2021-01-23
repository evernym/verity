package com.evernym.integrationtests.e2e.client

import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.actor.testkit.AgentDIDDetail
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.testkit.agentmsg.AgentMsgSenderHttpWrapper
import com.evernym.verity.testkit.mock.edge_agent.MockPairwiseConnDetail

trait ApiClientCommon { this: AgentMsgSenderHttpWrapper =>
  val DIDInfo: AgentDIDDetail = generateNewAgentDIDDetail()

  def getMyCloudAgentPairwiseDID(connId: String): String =
    mockClientAgent.pairwiseConnDetail(connId).myCloudAgentPairwiseDidPair.DID

  def getMsgSenderDID(connId: String): String =
    mockClientAgent.pairwiseConnDetail(connId).myPairwiseDidPair.DID

  def getPairwiseConnDetail(connId: String): MockPairwiseConnDetail = mockClientAgent.pairwiseConnDetail(connId)
}

trait AdminClient extends ApiClientCommon with BasicSpec { this: AgentMsgSenderHttpWrapper =>
  var agencyIdentity: AgencyPublicDid = _

  def checkIfListening(attempt: Int=0, maxAttempts: Int = 30, waitInSecondsAfterEachAttempt: Int = 3): Unit = {
    if (attempt == 1) {
      Thread.sleep(1000) //usual time taken by a process start after which it can responds to requests
    }
    if (attempt <= maxAttempts) {
      try {
        checkAppStatus()
      } catch {
        case _: Exception =>
          logApiFinish(s"will check again after $waitInSecondsAfterEachAttempt seconds")
          Thread.sleep(waitInSecondsAfterEachAttempt * 1000)
          checkIfListening(attempt + 1)
      }
    } else {
      throw new RuntimeException("app is not listening")
    }
  }
}