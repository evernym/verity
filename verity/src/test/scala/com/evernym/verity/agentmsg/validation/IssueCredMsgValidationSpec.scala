package com.evernym.verity.agentmsg.validation

import com.evernym.verity.Exceptions.InvalidValueException
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl.Offer
import com.evernym.verity.testkit.BasicSpec

class IssueCredMsgValidationSpec
  extends BasicSpec {

  "An Offer Message" - {

    "when constructed from valid json" - {
      "and called its validate method" - {
        "should be successful" in {
          List(
            """{"cred_def_id":"credDefId", "credential_values":{"name":"Alice", "age":"20"}}""",
            """{"cred_def_id":"credDefId", "credential_values":{"name":"Alice", "age":"20"}, "auto_issue":true}""",
            """{"cred_def_id":"credDefId", "credential_values":{"name":"Alice", "age":"20"}, "auto_issue":false}""",
            """{"cred_def_id":"credDefId", "credential_values":{"name":"Alice", "age":"20"}, "auto_issue":null}"""
          ).foreach { json =>
            val credOffer = JacksonMsgCodec.fromJson[Offer](json)
            credOffer.validate()
          }
        }
      }
    }

    "when constructed from invalid values" - {
      "and called its validate method" - {
        "should throw appropriate exception" in {
          val invalidJsons = List(
            """{"cred_def_id":"credDefId", "credential_values":{"name":"Alice", "age":"20"}, "auto_issue":"true"}""",
            """{"cred_def_id":"credDefId", "credential_values":{"name":"Alice", "age":"20"}, "auto_issue":"false"}""",
            """{"cred_def_id":"credDefId", "credential_values":{"name":"Alice", "age":"20"}, "auto_issue":1}"""
          )
          invalidJsons.foreach { json =>
            val ex = intercept[InvalidValueException] {
              val credOffer = JacksonMsgCodec.fromJson[Offer](json)
              credOffer.validate()
            }
            ex.getMessage.contains("""field 'auto_issue' has invalid value: """) shouldBe true
          }
        }
      }
    }

  }


}
