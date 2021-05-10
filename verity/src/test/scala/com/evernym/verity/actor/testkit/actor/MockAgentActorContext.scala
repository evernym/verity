package com.evernym.verity.actor.testkit.actor

import akka.actor.{ActorRef, ActorSystem}
import akka.Done
import com.evernym.verity.actor.StorageInfo
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter
import com.evernym.verity.agentmsg.msgpacker.AgentMsgTransformer
import com.evernym.verity.config.AppConfig
import com.evernym.verity.ledger.{LedgerPoolConnManager, LedgerSvc}
import com.evernym.verity.protocol
import com.evernym.verity.protocol.container.actor.ActorDriverGenParam
import com.evernym.verity.protocol.engine.{PinstIdResolution, ProtocolRegistry}
import com.evernym.verity.protocol.protocols.tictactoe.TicTacToeProtoDef
import com.evernym.verity.storage_services.StorageAPI
import com.evernym.verity.testkit.mock.ledger.InMemLedgerPoolConnManager
import com.evernym.verity.texter.SMSSender

import scala.concurrent.Future
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext

/**
 *
 * @param system
 * @param appConfig pre built config object
 */
class MockAgentActorContext(val system: ActorSystem,
                            val appConfig: AppConfig,
                            mockAgentMsgRouterProvider: () => Option[MockAgentMsgRouter] = { () => None })
  extends AgentActorContext {

  override lazy val smsSvc: SMSSender = new MockSMSSender(appConfig)
  override lazy val ledgerSvc: LedgerSvc = new MockLedgerSvc(system)

  override lazy val msgSendingSvc: MsgSendingSvcType = MockMsgSendingSvc
  override lazy val poolConnManager: LedgerPoolConnManager = new InMemLedgerPoolConnManager()(system.dispatcher)
  override lazy val agentMsgRouter: AgentMsgRouter = mockAgentMsgRouterProvider().getOrElse(
    new MockAgentMsgRouter(Map.empty)(appConfig, system)
  )
  override lazy val agentMsgTransformer: AgentMsgTransformer = new AgentMsgTransformer(walletAPI)

  override lazy val storageAPI: StorageAPI = new StorageAPI(appConfig) {
    var storageMock: Map[String, Array[Byte]] = Map()

    override def put(bucketName: String, id: String, data: Array[Byte]): Future[StorageInfo] = {
      storageMock += (id -> data)
      Future { StorageInfo("https://s3-us-west-2.amazonaws.com", "test-bucket") }
    }

    override def get(bucketName: String, id: String): Future[Array[Byte]] = {
      Future { storageMock(id) }
    }

    override def delete(bucketName: String, id: String): Future[Done] = {
      Future { storageMock -= id; Done }
    }
  }

  override lazy val protocolRegistry: ProtocolRegistry[ActorDriverGenParam] =
    ProtocolRegistry(protocol.protocols.protocolRegistry.entries :+
      ProtocolRegistry.Entry(TicTacToeProtoDef, PinstIdResolution.V0_2): _*)
}

case class MockAgentActorContextParam(actorTypeToRegions: Map[Int, ActorRef]=Map.empty)