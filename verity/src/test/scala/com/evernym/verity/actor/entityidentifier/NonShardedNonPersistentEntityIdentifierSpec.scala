package com.evernym.verity.actor.entityidentifier

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.constants.ActorNameConstants.DEFAULT_ENTITY_TYPE
import com.evernym.verity.actor.base.{ActorDetail, GetActorDetail}
import com.evernym.verity.actor.entityidentifier.base.{EntityIdentifierBaseSpec, MockNonPersistentActor}

class NonShardedNonPersistentEntityIdentifierSpec
  extends EntityIdentifierBaseSpec {

  lazy val mockActor = system.actorOf(MockNonPersistentActor.props, "MockActor")

  "A non sharded non persistent actor" - {
    "when asked for ActorDetail" - {
      "should respond with correct entity identifiers" in {
        mockActor ! GetActorDetail
        val actualDetail = expectMsgType[ActorDetail]
        val expectedDetail = ActorDetail(DEFAULT_ENTITY_TYPE, "MockActor", s"$DEFAULT_ENTITY_TYPE-MockActor")
        assertActorDetail(actualDetail, expectedDetail)
      }
    }
  }
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}

