package com.evernym.verity.http.rest

import java.util.UUID

import akka.util.ByteString
import com.evernym.verity.http.rest.base.RestApiBaseSpec
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.Msg.Message

/**
 * Purpose of this spec is to be able to establish connection between two users (mostly an enterprise and a consumer)
 * and to be able to exchange some messages over the established connection by using rest api
 */
class ExtendedRestApiSpec
  extends RestApiBaseSpec {

  lazy val createSchemaPayload: ByteString = ByteString(s"""{"@type":"did:sov:123456789abcdefghi1234;spec/write-schema/0.6/write","@id":"${UUID.randomUUID.toString}","name":"schema-name","version":"1.0","attrNames":["firstName","lastName"]}""")
  lazy val createConnectionPayload: ByteString = ByteString(s"""{"@type":"did:sov:123456789abcdefghi1234;spec/connecting/0.6/CREATE_CONNECTION","@id":"${UUID.randomUUID.toString}","sourceId": "${UUID.randomUUID.toString}","includePublicDID": false}""")

  val connId1 = "conn1"

  "prerequisite setup" - {
    testSetAppStateAsListening()
    testCheckAppStateIsListening()
    testAgencySetup()

    "agent setup for edge1" - {
      testAgentProvisioning(mockEntEdgeEnv)
      testValidUpdateComMethod(mockEntEdgeEnv)
    }

    "agent setup for edge2" - {
      testAgentProvisioning(mockUserEdgeEnv)
      testValidUpdateComMethod(mockUserEdgeEnv)
    }
  }

  "when enterprise user" - {
    "tried to setup issuer" - {
      "should be able to setup successfully" in {
        performIssuerSetup(mockEntRestEnv)
        performWriteSchema(mockEntRestEnv, createSchemaPayload)
      }
    }

    "tried to create connection request" - {
      "should be created successfully" in {
        createConnectionRequest(mockEntRestEnv, connId1, createConnectionPayload)
      }
    }
  }

  "when consumer user" - {
    "tried to create new relationship" - {
      "should be successful" in {
        createKeyRequest(mockUserRestEnv, connId1)
      }
    }

    "tried to accept connection request" - {
      "should be able to setup successfully" in {
        val invite = mockEntRestEnv.mockEnv.edgeAgent.inviteJsonObject
        acceptConnectionRequest(mockUserRestEnv, connId1, invite)
      }
    }
  }

  "when enterprise user" - {
    "tried to send message with invalid '~for_relationship'" - {
      "should respond with appropriate error" in {
        sendMsgWithLargeMsgForRel(mockEntRestEnv)
      }
    }
  }

  "when enterprise user" - {
    "tried to send message with someone else's '~for_relationship'" - {
      "should respond with appropriate error" in {
        sendMsgWithOthersMsgForRel(mockEntRestEnv, mockUserRestEnv.connRelRoutingDID(connId1))
      }
    }
  }

  "when enterprise user" - {
    "tried to send malicious cred offer" - {
      "should respond with appropriate error" in {
        sendMaliciousCredOffer(mockEntRestEnv)
      }
    }
  }

  "when enterprise user" - {
    "tried to send basic message" - {
      "should be successful" in {
        sendBasicMessage(mockEntRestEnv, connId1, UUID.randomUUID().toString)
      }
    }

    "consumer user" - {
      "should receive the sent message" in {
        val basicMessage = processLastReceivedPackedMsg[Message](mockUserRestEnv, connId1)
      }
    }
  }
  
}