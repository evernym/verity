package com.evernym.verity.agentmsg

import com.evernym.verity.actor.agent.msghandler.incoming.{IncomingMsgParam, STOP_GAP_MsgTypeMapper}
import com.evernym.verity.agentmsg.msgpacker.AgentMsgWrapper
import com.evernym.verity.protocol.engine.MsgFamily.EVERNYM_QUALIFIER
import com.evernym.verity.protocol.engine.MsgType
import com.evernym.verity.testkit.agentmsg.AgentMsgWrapperBuilder
import com.evernym.verity.testkit.BasicSpec


class STOP_GAP_MsgTypeMapperSpec extends BasicSpec {

  "STOP_GAP_MsgTypeMapper" - {
    "when asked to change msg type for non issue_credential 1.0 protocol messages" - {
      "should not change incoming msg param" in {
        val amw = AgentMsgWrapperBuilder.buildCreateAgentMsgWrapper_MFV_0_6
        val imp = IncomingMsgParam(amw, amw.headAgentMsgDetail.msgType)
        val newImp = STOP_GAP_MsgTypeMapper.changedMsgParam(imp)
        imp shouldBe newImp
      }
    }

    "when asked to change msg type for issue_credential 1.0 protocol messages" - {
      "should change msg type" in {
        val msgTypes = List (
          MsgType(EVERNYM_QUALIFIER, "credential_exchange", "1.0", "credential-offer"),
          MsgType(EVERNYM_QUALIFIER, "credential_exchange", "1.0", "credential-request"),
          MsgType(EVERNYM_QUALIFIER, "credential_exchange", "1.0", "credential"),
          MsgType(EVERNYM_QUALIFIER, "credential_exchange", "1.0", "presentation-request"),
          MsgType(EVERNYM_QUALIFIER, "credential_exchange", "1.0", "presentation"),
        )
        msgTypes.foreach { mt =>
          val amw = createAgentMsgWrapper(mt)
          val imp = IncomingMsgParam(amw, amw.headAgentMsgDetail.msgType)
          val newImp = STOP_GAP_MsgTypeMapper.changedMsgParam(imp)
          imp.msgType should not be newImp.msgType
          imp.givenMsg should not be newImp.givenMsg
        }
      }
    }

    def createAgentMsgWrapper(msgType: MsgType): AgentMsgWrapper = {
      //TODO: creating any agent message wrapper and just changing the msg type.
      val amw = AgentMsgWrapperBuilder.buildCreateAgentMsgWrapper_MFV_0_6
      STOP_GAP_MsgTypeMapper.changeMsgTypeInAgentMsgWrapper(amw, msgType)
    }
  }

}
