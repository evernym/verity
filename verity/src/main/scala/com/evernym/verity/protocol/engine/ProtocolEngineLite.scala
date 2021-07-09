package com.evernym.verity.protocol.engine

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.ServiceEndpoint
import com.evernym.verity.metrics.{MetricsWriterExtension, MetricsWriterExtensionImpl}
import com.evernym.verity.protocol.engine.asyncapi.ledger.LedgerAccess
import com.evernym.verity.protocol.engine.asyncapi.segmentstorage.{SegmentStoreAccess, StoredSegment}
import com.evernym.verity.protocol.engine.asyncapi.urlShorter.UrlShorteningAccess
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.protocol.engine.segmentedstate.SegmentStoreStrategy
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.{SegmentAddress, SegmentKey}
import com.evernym.verity.protocol.engine.util.{CryptoFunctions, SimpleLoggerLike}

import scala.util.Try


class ProtocolEngineLite(val sendsMsgs: SendsMsgs, val cryptoFunctions: CryptoFunctions, val engineLogger: SimpleLoggerLike) {

  type Container = BaseProtocolContainer[_,_,_,_,_,_]
  type Registration = (ProtoDef, ()=>RecordsEvents, SendsMsgs, ()=>Driver, Option[SegmentStoreStrategy])

  private var registry: Map[ProtoRef, Registration] = Map.empty
  private var containers: Map[PinstId, Container] = Map.empty //TODO reconcile with SimpleProtocolSystem

  def register(protoDef: ProtoDef, controllerGenerator: ()=>Driver,
               eventRecorderGenerator: ()=>RecordsEvents,
               segmentStoreStrategy: Option[SegmentStoreStrategy]): Unit = {
    registry = registry + (protoDef.msgFamily.protoRef ->
      (protoDef, eventRecorderGenerator, sendsMsgs, controllerGenerator, segmentStoreStrategy))
  }

  private def extension_!(protoRef: ProtoRef): Registration = {
    registry.getOrElse(protoRef, throw new RuntimeException(s"Protocol not registered: $protoRef"))
  }

  def handleMsg(pinstId: PinstId, msg: Any): Any = {
    val container = containers.getOrElse(pinstId, throw new RuntimeException(s"unknown protocol instance id: $pinstId"))
    container.handleMsg(msg)
  }

  def handleMsg(myDID: DID, theirDID: DID, threadId: ThreadId, protoRef: ProtoRef, msg: Any): PinstId = {
    val safeThreadId = cryptoFunctions.computeSafeThreadId(myDID, threadId)
    val pinstId = calcPinstId(safeThreadId, protoRef, msg)
    val container = getOrCreateContainer(myDID, theirDID, pinstId, protoRef)
    container.handleMsg(msg)
    pinstId
  }

  private def calcPinstId(safeThreadId: String, protoRef: ProtoRef, msg: Any): PinstId = {
    safeThreadId //do this for now, reconcile with other protocol systems
  }

  def processAllBoxes(): Unit = {
    containers.keys.foreach((pinstId: PinstId) => {
      containers(pinstId).processAllBoxes()
    })
  }

  class BaseProtocolContainer[P,R,M,E,S,I](val myDID: DID,
                                           val theirDID: DID,
                                           val pinstId: PinstId,
                                           val definition: ProtocolDefinition[P,R,M,E,S,I],
                                           val segmentStoreStrategy: Option[SegmentStoreStrategy],
                                           recordsEvents: RecordsEvents,
                                           msgSender: SendsMsgs,
                                           _driver: Driver) extends ProtocolContainer[P,R,M,E,S,I] {

    override def eventRecorder: RecordsEvents = recordsEvents
    override def sendsMsgs: SendsMsgs = msgSender
    override def driver: Option[Driver] = Option(_driver)
    override def createServices: Option[Services] = ???
    override def requestInit(): Unit = {
      val params = Parameters (
        definition.initParamNames map {
          case SELF_ID => Parameter(SELF_ID, myDID)
          case OTHER_ID => Parameter(OTHER_ID, theirDID)
        }
      )
      handleMsg(definition.createInitMsg(params))
    }


    override def metricsWriter: MetricsWriterExtensionImpl = ??? // todo

    override def segmentStore: SegmentStoreAccess = new SegmentStoreAccess {
      def storeSegment(segmentAddress: SegmentAddress,
                       segmentKey: SegmentKey,
                       segment: Any,
                       retentionPolicy: Option[String]=None)
                      (handler: Try[StoredSegment] => Unit): Unit = {}
      override def withSegment[T](segmentAddress: SegmentAddress,
                                  segmentKey: SegmentKey,
                                  retentionPolicy: Option[String]=None)
                                 (handler: Try[Option[T]] => Unit): Unit = {}
      override def removeSegment(segmentAddress: SegmentAddress,
                                 segmentKey: SegmentKey,
                                 retentionPolicy: Option[String])
                                (handler: Try[SegmentKey] => Unit): Unit = {}
    }

    override def wallet: WalletAccess = ???

    override def serviceEndpoint: ServiceEndpoint = ???

    override def ledger: LedgerAccess = ???

    override def urlShortening: UrlShorteningAccess = ???

    override def runAsyncOp(op: => Any): Unit = ???

  }

  //TODO merge with next
  private def getOrCreateContainer(myDID: DID, theirDID: DID, pinstId: PinstId, protoRef: ProtoRef): Container = {
    containers.getOrElse(pinstId, createContainer(myDID, theirDID, pinstId, extension_!(protoRef)))
  }


  private def createContainer[P,R,M,E,S,I](myDID: DID, theirDID: DID, pinstId: PinstId, reg: Registration): Container = {
    val container = new BaseProtocolContainer(myDID, theirDID, pinstId, reg._1, reg._5, reg._2(), reg._3, reg._4())
    containers = containers + (pinstId -> container)
    container.recoverOrInit()
    container
  }
}
