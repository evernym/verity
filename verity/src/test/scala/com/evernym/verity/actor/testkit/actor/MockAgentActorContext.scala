package com.evernym.verity.actor.testkit.actor

import akka.actor.{ActorRef, ActorSystem}
import akka.Done
import akka.http.scaladsl.model.{ContentType, ContentTypes}
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.StorageInfo
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter
import com.evernym.verity.agentmsg.msgpacker.AgentMsgTransformer
import com.evernym.verity.config.AppConfig
import com.evernym.verity.ledger.{LedgerPoolConnManager, LegacyLedgerSvc}
import com.evernym.verity.protocol
import com.evernym.verity.protocol.container.actor.ActorDriverGenParam
import com.evernym.verity.protocol.engine.MockVDRAdapter
import com.evernym.verity.protocol.engine.registry.{PinstIdResolution, ProtocolRegistry}
import com.evernym.verity.protocol.protocols.tictactoe.TicTacToeProtoDef
import com.evernym.verity.storage_services.StorageAPI
import com.evernym.verity.testkit.mock.ledger.InMemLedgerPoolConnManager
import com.evernym.verity.texter.SMSSender

import scala.concurrent.{ExecutionContext, Future}
import com.evernym.verity.transports.MsgSendingSvc
import com.evernym.verity.vdr.base.INDY_SOVRIN_NAMESPACE
import com.evernym.verity.vdr.{MockIndyLedger, MockLedgerRegistry, MockLedgerRegistryBuilder, MockVdrToolsBuilder, VDRAdapter}
import com.evernym.verity.vdr.service.{VDRToolsFactory, VdrTools}

/**
 *
 * @param system
 * @param appConfig pre built config object
 */
class MockAgentActorContext(val system: ActorSystem,
                            val appConfig: AppConfig,
                            val ecp: ExecutionContextProvider,
                            mockAgentMsgRouter: Option[AgentMsgRouter]=None)
  extends AgentActorContext {

  implicit lazy val executionContext: ExecutionContext = ecp.futureExecutionContext
  override lazy val smsSvc: SMSSender = new MockSMSSender(appConfig, executionContext)

  override lazy val msgSendingSvc: MsgSendingSvc = MockMsgSendingSvc
  override lazy val vdrBuilderFactory: VDRToolsFactory = () => new MockVdrToolsBuilder(ledgerRegistry)
  lazy val vdrTools: VdrTools = vdrBuilderFactory().build()
  override lazy val vdrAdapter: VDRAdapter = createVDRAdapter(vdrTools)
  override lazy val legacyLedgerSvc: LegacyLedgerSvc = new MockLegacyLedgerSvc(appConfig, vdrAdapter)
  override lazy val poolConnManager: LedgerPoolConnManager = new InMemLedgerPoolConnManager(ecp.futureExecutionContext, appConfig, vdrAdapter)
  override lazy val agentMsgRouter: AgentMsgRouter = mockAgentMsgRouter.getOrElse(
    new MockAgentMsgRouter(executionContext)(appConfig, system)
  )

  override lazy val agentMsgTransformer: AgentMsgTransformer = new AgentMsgTransformer(walletAPI, appConfig, executionContext)

  lazy val ledgerRegistry: MockLedgerRegistry =
    MockLedgerRegistryBuilder()
      .withLedger(INDY_SOVRIN_NAMESPACE, MockIndyLedger("genesis.txn file path", None))
      .build()


  override lazy val storageAPI: StorageAPI = new StorageAPI(appConfig, ecp.futureExecutionContext) {
    var storageMock: Map[String, Array[Byte]] = Map()

    override def put(bucketName: String,
                     id: String,
                     data: Array[Byte],
                     contentType: ContentType = ContentTypes.`application/octet-stream`): Future[StorageInfo] = {
      storageMock += (id -> data)
      Future { StorageInfo("https://s3-us-west-2.amazonaws.com") }
    }

    override def get(bucketName: String, id: String): Future[Option[Array[Byte]]] = {
      Future { storageMock.get(id) }
    }

    override def delete(bucketName: String, id: String): Future[Done] = {
      Future { storageMock -= id; Done }
    }

    override def ping: Future[Unit] = Future.successful((): Unit)
  }

  def createVDRAdapter(vdrTools: VdrTools)(implicit ec: ExecutionContext): VDRAdapter = {
    new MockVDRAdapter(vdrTools)(ec)
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
