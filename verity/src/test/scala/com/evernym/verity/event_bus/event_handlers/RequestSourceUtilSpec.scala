package com.evernym.verity.event_bus.event_handlers

import com.evernym.verity.protocol.engine.ProtoRef
import com.evernym.verity.protocol.engine.registry.PinstIdPair
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6.CredDefDefinition
import com.evernym.verity.protocol.protocols.writeSchema.v_0_6.WriteSchemaDefinition
import com.evernym.verity.testkit.BasicSpec


class RequestSourceUtilSpec
  extends BasicSpec {

  "RequestSourceUtil" - {
    "extract works" - {
      "when given valid request sources" - {
        "should be able process it successfully" in {
          checkRequestSource(
            "http://verity.avast.com/route/routeId1/protocol/write-schema/version/0.6/pinstid/pinstId123?threadId=threadId1",
            RequestSource("routeId1", PinstIdPair("pinstId123", WriteSchemaDefinition), "threadId1")
          )

          checkRequestSource(
            "https://verity.avast.com/route/routeId2/protocol/write-cred-def/version/0.6/pinstid/pinstId456?threadId=threadId1",
            RequestSource("routeId2", PinstIdPair("pinstId456", CredDefDefinition), "threadId1")
          )
        }
      }

      "when given invalid request sources" - {
        "should throw error" in {
          intercept[RuntimeException] {
            checkRequestSource(
              "http://verity.avast.com/route/routeId1/write-schema/version/0.6/pinstid/pinstId123?threadId=threadId1",
              RequestSource("routeId1", PinstIdPair("pinstId123", WriteSchemaDefinition), "threadId1")
            )
          }
        }
      }
    }
    "build works" - {
      "when given valid input" in {
        val reqSourceStr = RequestSourceUtil.build("https://verity.avast.com", "route123", ProtoRef("write-schema", "0.6"), "pinst123", "threadId1")
        reqSourceStr shouldBe "https://verity.avast.com/route/route123/protocol/write-schema/version/0.6/pinstid/pinst123?threadId=threadId1"
      }
    }
  }

  private def checkRequestSource(str: String, expectedReqSource: RequestSource): Unit = {
    val requestSource = RequestSourceUtil.extract(str)
    requestSource shouldBe expectedReqSource
  }
}
