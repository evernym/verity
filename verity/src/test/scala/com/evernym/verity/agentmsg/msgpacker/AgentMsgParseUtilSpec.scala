package com.evernym.verity.agentmsg.msgpacker

import com.evernym.verity.agentmsg.msgfamily.pairwise.SendRemoteMsgReq_MFV_0_6
import com.evernym.verity.testkit.BasicSpec

class AgentMsgParseUtilSpec
  extends BasicSpec {

  "when agent msg tried to be deserialized" - {
    "should be successful" in {
      val serMsg =
        """
          |{
          |  "@type": "did:sov:123456789abcdefghi1234;spec/pairwise/0.6/SEND_REMOTE_MSG",
          |  "@id": "766cb7b9-1c9b-40a5-a7d1-e58de4956f85",
          |  "mtype": "credOffer",
          |  "@msg": [99,114,101,100,32,111,102,102,101,114,32,109,115,103],
          |  "sendMsg": true
          |}
          |""".stripMargin
      val srm = AgentMsgParseUtil.convertTo[SendRemoteMsgReq_MFV_0_6](serMsg)
      srm.`@type` shouldBe "did:sov:123456789abcdefghi1234;spec/pairwise/0.6/SEND_REMOTE_MSG"
      srm.`@id` shouldBe "766cb7b9-1c9b-40a5-a7d1-e58de4956f85"
      srm.mtype shouldBe "credOffer"
      srm.`@msg` shouldBe Array(99,114,101,100,32,111,102,102,101,114,32,109,115,103)
      srm.sendMsg shouldBe true
    }
  }
}
