package com.evernym.verity.protocol.engine

import com.evernym.verity.ServiceEndpoint
import com.evernym.verity.protocol.engine.external_api_access.{LedgerAccess, WalletAccess}
import com.evernym.verity.protocol.engine.segmentedstate.{SegmentStoreStrategy, SegmentedStateMsg}
import com.evernym.verity.protocol.engine.urlShortening.UrlShorteningAccess
import com.evernym.verity.protocol.protocols.tictactoe.State.Offered
import com.evernym.verity.protocol.protocols.tictactoe.{Accepted, State, TicTacToe, TicTacToeProtoDef, Role => TicTacToeRole}
import com.evernym.verity.testkit.BasicSpec

import scala.util.Try

class ProtocolContainerSpec extends BasicSpec {
  "ProtocolContainer" - {
    "recoverOrInit()" - {
      "should apply the state and then the events" in {
        class TestProtocolContainer[P,R,M,E,S,I](val definition: ProtocolDefinition[P,R,M,E,S,I])
          extends ProtocolContainer[P,R,M,E,S,I] { // [TicTacToe, Role, Any, Any, State, String]
          override def pinstId: PinstId = "12345"

          override def eventRecorder: RecordsEvents = new RecordsEvents {
            override def recoverState(pinstId: PinstId): (_, Vector[_]) = (Offered(), Vector(Accepted()))

            override def record(pinstId: PinstId, event: Any, state: Any, cb: Any => Unit): Unit = ???
          }

          override def storageService: StorageService = new StorageService {
            override def read(id: VerKey, cb: Try[Array[Byte]] => Unit): Unit = {}

            override def write(id: VerKey, data: Array[Byte], cb: Try[Any] => Unit): Unit = {}
          }

          override def sendsMsgs: SendsMsgs = ???

          override def driver: Option[Driver] = ???

          override def createServices: Option[Services] = ???

          override def requestInit(): Unit = None // Do nothing

          def handleSegmentedMsgs(msg: SegmentedStateMsg, postExecution: Either[Any, Option[Any]] => Unit): Unit = ???

          override def wallet: WalletAccess = ???

          override def serviceEndpoint: ServiceEndpoint = ???

          override def addToMsgQueue(msg: Any): Unit = ???

          override def segmentStoreStrategy: Option[SegmentStoreStrategy] = ???

          override def ledger: LedgerAccess = ???

          override def urlShortening: UrlShorteningAccess = ???
        }

        val container = new TestProtocolContainer[TicTacToe, TicTacToeRole, Any, Any, State, String](TicTacToeProtoDef)

        container.state shouldBe a [State.Uninitialized]
        container.recoverOrInit()
        container.state shouldBe a [State.Accepted]
      }
    }
  }
}
