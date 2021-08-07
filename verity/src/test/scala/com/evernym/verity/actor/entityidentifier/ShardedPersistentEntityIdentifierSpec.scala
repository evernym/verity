package com.evernym.verity.actor.entityidentifier

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.base.ActorDetail
import com.evernym.verity.actor.entityidentifier.base.{EntityIdentifierBaseSpec, MockPersistentActor}
import com.evernym.verity.actor.persistence.{GetPersistentActorDetail, PersistentActorDetail}
import com.evernym.verity.actor.{ForIdentifier, ShardUtil}

import scala.concurrent.ExecutionContext

class ShardedPersistentEntityIdentifierSpec
  extends EntityIdentifierBaseSpec
    with ShardUtil {

  lazy val mockActorRegion = createNonPersistentRegion(
    "MockActor",
    MockPersistentActor.props(appConfig, futureExecutionContext)
  )
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  lazy val futureExecutionContext: ExecutionContext = ecp.futureExecutionContext

  "A sharded persistent actor" - {
    "when asked for GetPersistentActorDetail" - {
      "should respond with correct entity identifiers" in {
        mockActorRegion ! ForIdentifier("1", GetPersistentActorDetail)
        val actualDetail = expectMsgType[PersistentActorDetail]
        val expectedDetail = PersistentActorDetail(
          ActorDetail("MockActor", "1", "MockActor-1"),
          "MockActor-1", 0, 0
        )
        assertPersistentActorDetail(actualDetail, expectedDetail)
      }
    }
  }

  override def executionContextProvider: ExecutionContextProvider = ecp
}

