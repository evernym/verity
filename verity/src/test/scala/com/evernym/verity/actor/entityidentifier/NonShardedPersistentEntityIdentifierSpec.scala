package com.evernym.verity.actor.entityidentifier

import com.evernym.verity.actor.base.ActorDetail
import com.evernym.verity.constants.ActorNameConstants.DEFAULT_ENTITY_TYPE
import com.evernym.verity.actor.entityidentifier.base.{EntityIdentifierBaseSpec, MockPersistentActor}
import com.evernym.verity.actor.persistence.{GetPersistentActorDetail, PersistentActorDetail}

class NonShardedPersistentEntityIdentifierSpec
  extends EntityIdentifierBaseSpec {

  lazy val mockActor = system.actorOf(MockPersistentActor.props(appConfig), "MockActor")

  "A non sharded persistent actor" - {
    "when asked for PersistentActorDetail" - {
      "should respond with correct entity identifiers" in {
        mockActor ! GetPersistentActorDetail
        val actualDetail = expectMsgType[PersistentActorDetail]
        val expectedDetail =
          PersistentActorDetail(
            ActorDetail(DEFAULT_ENTITY_TYPE, "MockActor", s"$DEFAULT_ENTITY_TYPE-MockActor"),
            "Default-MockActor", 0, 0
          )
        assertPersistentActorDetail(actualDetail, expectedDetail)
      }
    }
  }
}

