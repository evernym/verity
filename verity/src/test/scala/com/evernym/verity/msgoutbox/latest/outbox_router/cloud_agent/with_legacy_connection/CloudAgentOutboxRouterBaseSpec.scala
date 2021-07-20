package com.evernym.verity.msgoutbox.latest.outbox_router.cloud_agent.with_legacy_connection

import com.evernym.verity.msgoutbox.latest.outbox_router.{AgentContext, OutboxRouterSpecBase, PairwiseRelContext, SelfRelContext}
import com.evernym.verity.protocol.engine.ParticipantId
import com.evernym.verity.util.ParticipantUtil

trait CloudAgentOutboxRouterBaseSpec
  extends OutboxRouterSpecBase

case object VerityCloudAgent // mimicing CAS/EAS scenario
  extends AgentContext {

  val selfRelContext: SelfRelContext = new SelfRelContext {
    val myDID = "selfRelDID"
    val thisAgentKeyId = "thisSelfRelAgentKeyId"
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

    def theirPairwiseDID = "theirPairwiseDID"
    def otherParticipantId = theirPairwiseDID
  }
}