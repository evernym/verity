package com.evernym.verity.eventing.event_handlers

import com.evernym.verity.eventing.event_handlers.RequestSourceUtil.REQ_SOURCE_PREFIX
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
          extractRequestSource(
            s"$REQ_SOURCE_PREFIX/domainId1/relId1/protocol/write-schema/0.6/pinstId123?threadId=threadId1",
            RequestSource("domainId1", "relId1", "threadId1", PinstIdPair("pinstId123", WriteSchemaDefinition))
          )

          extractRequestSource(
            s"$REQ_SOURCE_PREFIX/domainId2/relId2/protocol/write-cred-def/0.6/pinstId123?threadId=threadId2",
            RequestSource("domainId2", "relId2", "threadId2", PinstIdPair("pinstId123", CredDefDefinition))
          )
        }
      }

      "when given invalid request sources" - {
        "should throw error" in {
          intercept[RuntimeException] {
            RequestSourceUtil.extract("http://verity.avast.com/route/routeId1/write-schema/0.6/pinstId123?threadId=threadId1")
          }
        }
      }
    }

    "build works" - {
      "when given valid input" in {
        val reqSourceStr = RequestSourceUtil.build(s"domainId", "relId", "pinst123", "threadId1", ProtoRef("write-schema", "0.6"))
        reqSourceStr shouldBe "event-source://v1:ssi:protocol/domainId/relId/protocol/write-schema/0.6/pinst123?threadId=threadId1"
      }
    }
  }

  private def extractRequestSource(str: String, expectedReqSource: RequestSource): Unit = {
    val requestSource = RequestSourceUtil.extract(str)
    requestSource shouldBe expectedReqSource
  }
}
