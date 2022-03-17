package com.evernym.verity.event_bus.event_handlers

import com.evernym.verity.event_bus.RequestSource
import com.evernym.verity.protocol.engine.registry.PinstIdPair
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6.CredDefDefinition
import com.evernym.verity.protocol.protocols.writeSchema.v_0_6.WriteSchemaDefinition
import com.evernym.verity.testkit.BasicSpec


class RequestSourceBuilderSpec
  extends BasicSpec {

  "RequestSourceBuilder" - {
    "when given valid request sources" - {
      "should be able process it successfully" in {
        checkRequestSource(
          "http://verity.avast.com/route/routeId1/protocol/write-schema/version/0.6/pinstid/pinstId123",
          RequestSource("routeId1", PinstIdPair("pinstId123", WriteSchemaDefinition))
        )

        checkRequestSource(
          "https://verity.avast.com/route/routeId2/protocol/write-cred-def/version/0.6/pinstid/pinstId456",
          RequestSource("routeId2", PinstIdPair("pinstId456", CredDefDefinition))
        )
      }
    }

    "when given invalid request sources" - {
      "should throw error" in {
        intercept[RuntimeException] {
          checkRequestSource(
            "http://verity.avast.com/route/routeId1/write-schema/version/0.6/pinstid/pinstId123",
            RequestSource("routeId1", PinstIdPair("pinstId123", WriteSchemaDefinition))
          )
        }
      }
    }
  }

  private def checkRequestSource(str: String, expectedReqSource: RequestSource): Unit = {
    val requestSource = RequestSourceBuilder.build(str)
    requestSource shouldBe expectedReqSource
  }
}
