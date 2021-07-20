package com.evernym.verity.msgoutbox.latest.outbox_router.edge_agent

import com.evernym.verity.msgoutbox.latest.outbox_router.{AgentContext, OutboxRouterSpecBase, PairwiseRelContext, SelfRelContext}
import com.evernym.verity.util.ParticipantUtil

trait EdgeAgentOutboxRouterBaseSpec
  extends OutboxRouterSpecBase

case object VerityEdgeAgent   // mimicing VAS scenario
  extends AgentContext {

  val selfRelContext: SelfRelContext = new SelfRelContext {
    val myDID = "selfRelDID"
    val thisAgentKeyId = myDID
    val selfParticipantId = ParticipantUtil.participantId(thisAgentKeyId, Option(thisAgentKeyId))
    val myDomainParticipantId = ParticipantUtil.participantId(thisAgentKeyId, Option(myDID))
    val otherParticipantId = myDomainParticipantId
  }

  val pairwiseRelContext: PairwiseRelContext = new PairwiseRelContext {
    val selfRelDID = selfRelContext.myDID
    val thisAgentKeyId = "thisPairwiseAgentKeyId"

    val myPairwiseDID = "myPairwiseDID"
    val selfParticipantId = myPairwiseDID
    val myDomainParticipantId = ParticipantUtil.participantId(thisAgentKeyId, Option(selfRelDID))

    val theirPairwiseDID = "theirPairwiseDID"
    val otherParticipantId = theirPairwiseDID
  }
}
