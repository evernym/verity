package com.evernym.verity.actor.agent.msgrouter

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.RouteSet
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.testkit.BasicAsyncSpec


class AgentMsgRouterSpec extends PersistentActorSpec with AgentMsgRouteSpecValues with BasicAsyncSpec {

  "AgentMsgRouter" - {

    "when send SetRoute with a DID" - {
      "should be able to set route" in {
        val setRoute = SetRoute(did1, ActorAddressDetail(actorTypeId, address))
        val futResp = agentMsgRouter.execute(setRoute)
        futResp.mapTo[RouteSet].map { rs =>
          rs.actorTypeId shouldBe actorTypeId
          rs.address shouldBe address
        }
      }
    }

    "when send GetRoute with a corresponding ver key" - {
      "should be able to get route" in {
        val getRoute = GetRoute(verkey1)
        val futResp = agentMsgRouter.execute(getRoute)
        futResp.mapTo[Option[ActorAddressDetail]].map { rd =>
          rd.isDefined shouldBe true
          rd.get.actorTypeId shouldBe actorTypeId
          rd.get.address shouldBe address
        }
      }
    }

    "when send GetRoute with a correct DID" - {
      "should be able to get route" in {
        val getRoute = GetRoute(did1)
        val futResp = agentMsgRouter.execute(getRoute)
        futResp.mapTo[Option[ActorAddressDetail]].map { rd =>
          rd.isDefined shouldBe true
          rd.get.actorTypeId shouldBe actorTypeId
          rd.get.address shouldBe address
        }
      }
    }

    "when send GetRoute with a wrong DID" - {
      "should be able to get route" in {
        val getRoute = GetRoute(did2)
        val futResp = agentMsgRouter.execute(getRoute)
        futResp.mapTo[Option[ActorAddressDetail]].map { rd =>
          rd.isEmpty shouldBe true
        }
      }
    }

    "when send SetRoute again" - {
      "should NOT be able to update route" in {
        val setRoute = SetRoute(did1, ActorAddressDetail(actorTypeId, address))
        val futResp = agentMsgRouter.execute(setRoute)
        futResp.mapTo[RouteAlreadySet].map { rs =>
          rs.forDID shouldBe did1
        }
      }
    }
  }

  def agentMsgRouter: AgentMsgRouter = platform.agentActorContext.agentMsgRouter
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}



trait AgentMsgRouteSpecValues {
  val verkey1 = "8HH5gYEeNc3z7PYXmd54d4x6qAfCNrqQqEB3nS7Zfu7K"
  val did1 = "EMmo7oSqQk1twmgLDRNjzC"
  val verkey2 = "92bogDLJPDiuzyQ8JN9xQoRYzNxNB9ZmJz5jC42dfkKv"
  val did2 = "FjG1c6VFBYDFj42qFhyAG5"
  val actorTypeId = 1
  val address = "address"
}
