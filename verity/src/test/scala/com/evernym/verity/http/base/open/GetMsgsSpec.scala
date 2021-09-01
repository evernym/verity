package com.evernym.verity.http.base.open

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.util2.Status.StatusDetail
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.actor.agent.user.msgstore.MsgDetail
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.http.base.{EndpointHandlerBaseSpec, HasMsgStore}
import com.evernym.verity.protocol.engine.Constants.MFV_1_0
import com.evernym.verity.testkit.agentmsg.AgentMsgPackagingContext
import com.evernym.verity.testkit.util.MsgsByConns_MFV_0_6
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.testkit.mock.agent.{MockEdgeAgent, MockEnv}
import org.scalatest.time.{Seconds, Span}

trait GetMsgsSpec extends HasMsgStore { this : EndpointHandlerBaseSpec =>

  type ConnId = String
  /**
   * this tests that there is no messages returned from GET_MSG_BY_CONNS api
   */
  def testGetMsgsByConnections(mockEnv: MockEnv,
                               totalConns: Int,
                               emc: Map[ConnId, ExpectedMsgCriteria]=Map.empty): Unit = {

    val mockEdgeAgent = mockEnv.edgeAgent

    s"when sent GET_MSGS_BY_CONNS 0.5 when there is no msgs ($totalConns:${emc.values.map(_.totalMsgs.toString)})" - {
      "should respond with MSGS_BY_CONNS" in {
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareGetMsgsFromConns().msg) ~> epRoutes ~> check {
          status shouldBe OK
          val gm = mockEdgeAgent.v_0_5_resp.handleGetMsgsFromConnsResp(PackedMsg(responseAs[Array[Byte]]))
          gm.msgsByConns.size shouldBe totalConns
          emc.foreach { emc =>
            val ams = gm.msgsByConns.find(_.pairwiseDID == pairwiseIdForConn(mockEdgeAgent, emc._1)).get
            addToMsgStore(emc._1, ams.msgs)
            emc._2.totalMsgs.forall(_ == ams.msgs.size) shouldBe true
            checkMsgs(ams.msgs, emc._2.expectedMsgs)
          }
        }
      }
    }

    s"when sent GET_MSGS_BY_CONNS 0.6 when there is no msgs ($totalConns:${emc.values.map(_.totalMsgs.toString)})" - {
      "should respond with MSGS_BY_CONNS" in {
        implicit val msgPackagingContext = AgentMsgPackagingContext(MPF_INDY_PACK, MFV_1_0, packForAgencyRoute = true)
        buildAgentPostReq(mockEdgeAgent.v_0_6_req.prepareGetMsgsFromConns().msg) ~> epRoutes ~> check {
          status shouldBe OK
          val gm = mockEdgeAgent.v_0_6_resp.handleGetMsgsByConnsResp(PackedMsg(responseAs[Array[Byte]]))
          gm.msgsByConns.size shouldBe emc.size
          emc.foreach { emc =>
            val ams = gm.msgsByConns.find(_.pairwiseDID == pairwiseIdForConn(mockEdgeAgent, emc._1)).get
            addToMsgStore(emc._1, ams.msgs)
            emc._2.totalMsgs.forall(_ == ams.msgs.size) shouldBe true
            checkMsgs(ams.msgs, emc._2.expectedMsgs)
          }
        }
      }
    }

    s"when sent GET_MSGS_BY_CONNS 0.6 by non-existent uuid when there is no msgs ($totalConns:${emc.values.map(_.totalMsgs.toString)})" - {
      "should respond with empty msgs" in {
        implicit val msgPackagingContext: AgentMsgPackagingContext = AgentMsgPackagingContext(MPF_INDY_PACK, MFV_1_0, packForAgencyRoute = true)
        val uids: Option[List[String]] = Some(List("non-existent-uuid"))
        buildAgentPostReq(mockEdgeAgent.v_0_6_req.prepareGetMsgsFromConns(uids=uids).msg) ~> epRoutes ~> check {
          status shouldBe OK
          val gm: MsgsByConns_MFV_0_6 = mockEdgeAgent.v_0_6_resp.handleGetMsgsByConnsResp(PackedMsg(responseAs[Array[Byte]]))
          gm.msgsByConns.size shouldBe emc.size
          emc.foreach { emc =>
            val ams = gm.msgsByConns.find(_.pairwiseDID == pairwiseIdForConn(mockEdgeAgent, emc._1)).get
            ams.msgs.isEmpty shouldBe true
          }
        }
      }
    }
  }

  /**
   *
   * @param connId connection id
   * @param emc expected message criteria
   */
  def testGetMsgsFromConnection(mockEdgeAgent: MockEdgeAgent, connId: String, emc: ExpectedMsgCriteria): Unit = {
    s"when sent GET_MSGS when there are ${emc.hint(connId)}" - {
      "should respond with MSGS with empty msg list" taggedAs UNSAFE_IgnoreLog in {
        eventually (timeout(Span(10, Seconds)), interval(Span(9, Seconds))){
          buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareGetMsgsFromConn(connId).msg) ~> epRoutes ~> check {
            status shouldBe OK
            val gmr = mockEdgeAgent.v_0_5_resp.handleGetMsgsRespFromConn(PackedMsg(responseAs[Array[Byte]]), buildConnIdMap(connId))
            emc.totalMsgs.forall(_ == gmr.msgs.size) shouldBe true
            addToMsgStore(connId, gmr.msgs)
            checkMsgs(gmr.msgs, emc.expectedMsgs)
          }
        }
      }
    }
  }

  private def checkMsgs(actualMsgs: List[MsgDetail], hasMsgs: List[ExpectedMsgDetail]): Unit = {
    hasMsgs.forall { em =>
      actualMsgs.exists(rm => rm.`type` == em.typ && rm.statusCode == em.statusDetail.statusCode)
    } shouldBe true
  }

  private def pairwiseIdForConn(mockEdgeAgent: MockEdgeAgent, connId: String): String =
    mockEdgeAgent.pairwiseConnDetails(connId).myPairwiseDidPair.did

}

case class ExpectedMsgDetail(typ: String, statusDetail: StatusDetail)

object ExpectedMsgCriteria {
  def apply(totalMsgs: Int): ExpectedMsgCriteria = ExpectedMsgCriteria(List.empty, Option(totalMsgs))
  def apply(totalMsgs: Int, expectedMsgs: List[ExpectedMsgDetail]):ExpectedMsgCriteria =
    ExpectedMsgCriteria(expectedMsgs, Option(totalMsgs))
}

/**
 *
 * @param expectedMsgs list of messages which should be present in the received messages
 * @param totalMsgs how many total messages expected
 */
case class ExpectedMsgCriteria(expectedMsgs: List[ExpectedMsgDetail] = List.empty, totalMsgs: Option[Int]=None) {

  def hint(connId: String): String = s"${totalMsgs.map(tm => s"$tm messages ").getOrElse("")}" +
    s"(expecting: $connId:${expectedMsgs.map(_.typ).mkString(",")})"
}
