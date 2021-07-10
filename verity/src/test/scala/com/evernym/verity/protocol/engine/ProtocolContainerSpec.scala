package com.evernym.verity.protocol.engine

import com.evernym.verity.util2.ServiceEndpoint
import com.evernym.verity.protocol.engine.asyncapi.ledger.LedgerAccess
import com.evernym.verity.protocol.engine.asyncapi.segmentstorage.{SegmentStoreAccess, StoredSegment}
import com.evernym.verity.protocol.engine.asyncapi.urlShorter.UrlShorteningAccess
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.{SegmentAddress, SegmentKey}
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

            override def record(pinstId: PinstId, event: Any, state: Any)(cb: Any => Unit): Unit = ???
          }

          override def segmentStore: SegmentStoreAccess = new SegmentStoreAccess {
            def storeSegment(segmentAddress: SegmentAddress,
                             segmentKey: SegmentKey,
                             segment: Any,
                             retentionPolicy: Option[String]=None)
                            (handler: Try[StoredSegment] => Unit): Unit = {}
            def withSegment[T](segmentAddress: SegmentAddress,
                               segmentKey: SegmentKey,
                               retentionPolicy: Option[String]=None)
                              (handler: Try[Option[T]] => Unit): Unit = {}
            override def removeSegment(segmentAddress: SegmentAddress,
                                       segmentKey: SegmentKey,
                                       retentionPolicy: Option[String])
                                      (handler: Try[SegmentKey] => Unit): Unit = {}
          }

          override def sendsMsgs: SendsMsgs = ???

          override def driver: Option[Driver] = ???

          override def createServices: Option[Services] = ???

          override def requestInit(): Unit = None // Do nothing

          override def wallet: WalletAccess = ???

          override def serviceEndpoint: ServiceEndpoint = ???

          override def ledger: LedgerAccess = ???

          override def urlShortening: UrlShorteningAccess = ???

          override def runAsyncOp(op: => Any): Unit = ???
        }

        val container = new TestProtocolContainer[TicTacToe, TicTacToeRole, Any, Any, State, String](TicTacToeProtoDef)

        container.state shouldBe a [State.Uninitialized]
        container.recoverOrInit()
        container.state shouldBe a [State.Accepted]
      }
    }
  }
}
