package com.evernym.verity.actor.entityidentifier

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.{ForIdentifier, ShardUtil}
import com.evernym.verity.actor.base.{ActorDetail, GetActorDetail}
import com.evernym.verity.actor.entityidentifier.base.{EntityIdentifierBaseSpec, MockNonPersistentActor}

class ShardedNonPersistentEntityIdentifierSpec
  extends EntityIdentifierBaseSpec
    with ShardUtil {

  lazy val mockActorRegion = createNonPersistentRegion(
    "MockActor",
    MockNonPersistentActor.props
  )

  "A sharded non persistent actor" - {
    "when asked for ActorDetail" - {
      "should respond with correct entity identifiers" in {
        mockActorRegion ! ForIdentifier("1", GetActorDetail)
        val actualDetail = expectMsgType[ActorDetail]
        val expectedDetail = ActorDetail("MockActor", "1", "MockActor-1")
        assertActorDetail(actualDetail, expectedDetail)
      }
    }
  }
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}

