//package com.evernym.verity.protocol.protocols.connecting.v_0_6
//
//import com.evernym.verity.agentmsg.msgfamily.MsgFamilyCommon._
//import com.evernym.verity.agentmsg.msgfamily.pairwise.CreateConnectionReqMsg_MFV_0_6
//import com.evernym.verity.protocol.testkit.TestsProtocolsImpl
//
//class ConnectingProtocolSpec extends TestsProtocolsImpl(ConnectingProtoDef) with BasicFixtureSpec {
//
//  "Connecting Protocol v1.0" - {
//    "has two roles" ignore { _ =>
//      //TODO: we need to complete this once we are done with fixing other pending things.
//      ConnectingProtoDef.roles.size shouldBe 2
//    }
//    "Inviter" - {
//      "can initiate a connection" in { f =>
//
//        //alice is Acme in this scenario
//        //bob is just plain old bob
//
//        val bobPhone: String = "+1-801-376-3348"
//        val sourceId: String = "bob472"
//        val usePubDID: Boolean = false
//
//        f.alice ~ CreateConnectionReqMsg_MFV_0_6(MSG_TYPE_DETAIL_CREATE_CONNECTION, sourceId, Option(bobPhone))
//
//        //todo check state on Alice
//        //todo check state on Bob
//
//        val bobsSourceId: String = "Acme"
//        f.bob ~ AcceptConnection(bobsSourceId)
//
//        //todo check state on Alice
//        //todo check state on Bob
//
//
//
//      }
//    }
//  }
//}
//
//
