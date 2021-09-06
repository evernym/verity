package com.evernym.verity.actor.testkit.actor

import akka.actor.{ActorRef, ActorSystem}
import akka.Done
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.StorageInfo
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter
import com.evernym.verity.agentmsg.msgpacker.AgentMsgTransformer
import com.evernym.verity.config.AppConfig
import com.evernym.verity.ledger.{LedgerPoolConnManager, LedgerSvc}
import com.evernym.verity.protocol
import com.evernym.verity.protocol.container.actor.ActorDriverGenParam
import com.evernym.verity.protocol.engine.registry.{PinstIdResolution, ProtocolRegistry}
import com.evernym.verity.protocol.protocols.tictactoe.TicTacToeProtoDef
import com.evernym.verity.storage_services.StorageAPI
import com.evernym.verity.testkit.mock.ledger.InMemLedgerPoolConnManager
import com.evernym.verity.texter.SMSSender

import scala.concurrent.{ExecutionContext, Future}
import com.evernym.verity.transports.MsgSendingSvc

/**
 *
 * @param system
 * @param appConfig pre built config object
 */
class MockAgentActorContext(val system: ActorSystem,
                            val appConfig: AppConfig,
                            val ecp: ExecutionContextProvider,
                            mockAgentMsgRouterProvider: () => Option[MockAgentMsgRouter] = { () => None })
  extends AgentActorContext {

  implicit lazy val executionContext: ExecutionContext = ecp.futureExecutionContext
  override lazy val smsSvc: SMSSender = new MockSMSSender(appConfig, executionContext)
  override lazy val ledgerSvc: LedgerSvc = new MockLedgerSvc(system, ecp.futureExecutionContext)

  override lazy val msgSendingSvc: MsgSendingSvc = MockMsgSendingSvc
  override lazy val poolConnManager: LedgerPoolConnManager = new InMemLedgerPoolConnManager(ecp.futureExecutionContext)(system.dispatcher)
  override lazy val agentMsgRouter: AgentMsgRouter = mockAgentMsgRouterProvider().getOrElse(
    new MockAgentMsgRouter(executionContext, Map.empty)(appConfig, system)
  )

  override lazy val agentMsgTransformer: AgentMsgTransformer = new AgentMsgTransformer(walletAPI, appConfig, executionContext)

  override lazy val storageAPI: StorageAPI = new StorageAPI(appConfig, ecp.futureExecutionContext) {
    var storageMock: Map[String, Array[Byte]] = Map()

    override def put(bucketName: String, id: String, data: Array[Byte]): Future[StorageInfo] = {
      storageMock += (id -> data)
      Future { StorageInfo("https://s3-us-west-2.amazonaws.com") }
    }

    override def get(bucketName: String, id: String): Future[Option[Array[Byte]]] = {
      Future { storageMock.get(id) }
    }

    override def delete(bucketName: String, id: String): Future[Done] = {
      Future { storageMock -= id; Done }
    }
  }

  override lazy val protocolRegistry: ProtocolRegistry[ActorDriverGenParam] =
    ProtocolRegistry(protocol.protocols.protocolRegistry.entries :+
      ProtocolRegistry.Entry(TicTacToeProtoDef, PinstIdResolution.V0_2): _*)

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
}

case class MockAgentActorContextParam(actorTypeToRegions: Map[Int, ActorRef]=Map.empty)
