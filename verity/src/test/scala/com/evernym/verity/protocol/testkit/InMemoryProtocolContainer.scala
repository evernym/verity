package com.evernym.verity.protocol.testkit

import com.evernym.verity.util2.ServiceEndpoint
import com.evernym.verity.actor.agent.relationship.Relationship
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.metrics.writer.NoOpMetricsWriter
import com.evernym.verity.protocol.container.actor.ServiceDecorator
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.ledger.LedgerAccess
import com.evernym.verity.protocol.engine.asyncapi.segmentstorage.{SegmentStoreAccess, StoredSegment}
import com.evernym.verity.protocol.engine.asyncapi.urlShorter.UrlShorteningAccess
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.protocol.engine.journal.JournalContext
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.{SegmentAddress, SegmentKey}
import com.typesafe.scalalogging.Logger
import scalapb.GeneratedMessage

import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.Try

case class ProtocolContainerElements[P,R,M,E,S,I](system: SimpleProtocolSystem,
                                                  participantId: ParticipantId,
                                                  pinstId: PinstId,
                                                  threadId: Option[ThreadId],
                                                  definition: ProtocolDefinition[P,R,M,E,S,I],
                                                  initProvider: InitProvider,
                                                  eventRecorder: Option[RecordsEvents]=None,
                                                  driver: Option[Driver]=None,
                                                  parentLogContext: JournalContext=JournalContext(),
                                                  walletAccessProvider: Option[()=>WalletAccess] = None,
                                                  ledgerAccessProvider: Option[()=>LedgerAccess] = None,
                                                  urlShorteningAccessProvider: Option[()=>UrlShorteningAccess] = None)

/**
  * Protocols may run standalone but they most likely will be run in
  * some kind of container that can feed them generalized services,
  * like Recording of Events. This container is aware of a pairwise
  * relationship between two participants. This is a simple
  * synchronous container that allows for easy testing of protocols.
  *
  * @tparam E Event type
  */
class InMemoryProtocolContainer[P,R,M,E,S,I](val pce: ProtocolContainerElements[P,R,M,E,S,I])(implicit tag: ClassTag[M])
  extends {
    val pinstId = pce.pinstId
    val definition = pce.definition
    val driver = pce.driver
    val participantId = pce.participantId
    val system = pce.system
    val initProvider = pce.initProvider
    override val threadId = pce.threadId
  } with ProtocolContainer[P,R,M,E,S,I]
    with HasInbox[Any,Any] {


  override def metricsWriter: MetricsWriter = new NoOpMetricsWriter // todo check it works!

  override val _threadId: Option[ThreadId] = pce.threadId
  override val _storageId: Option[StorageId] = Some(pce.pinstId)
  override lazy val journalContext: JournalContext = pce.parentLogContext + pinstId.take(5)

  val sendsMsgs: SendsMsgs = new SendsMsgsForContainer[M](this) {
    def send(pmsg: ProtocolOutgoingMsg): Unit = {
      pmsg match {
        case ProtocolOutgoingMsg(s: ServiceDecorator, to, from, mId, tId, pId, pDef) =>
          pce.system.handleOutMsg(ProtocolOutgoingMsg(s.msg, to, from, mId, tId, pId, pDef).envelope)
        case pom: ProtocolOutgoingMsg  => pce.system.handleOutMsg(pom.envelope)
      }
    }

    override def sendSMS(toPhoneNumber: String, msg: String): Future[String] = ???
  }

  val eventRecorder: RecordsEvents = pce.eventRecorder.getOrElse(new SimpleEventRecorder(definition.initialState))
  val segmentStore: SegmentStoreAccess = new MockStorageService(system)
  implicit val asyncOpRunner = this

  def registerWithSystem(): Unit = pce.system.register(this)

  override def createServices: Option[Services] = None

  def requestInit(): Unit = pce.initProvider.request(this)

  override lazy val wallet: WalletAccess = pce
    .walletAccessProvider
    .map(_())
    .getOrElse(throw new RuntimeException("no wallet access provided to container"))

  override lazy val ledger: LedgerAccess = pce
    .ledgerAccessProvider
    .map(_())
    .getOrElse(throw new RuntimeException("no ledger requests access provided to container"))

  override def serviceEndpoint: ServiceEndpoint = s"http://www.example.com/$participantId"

  registerWithSystem()

  override def urlShortening: UrlShorteningAccess =
    pce.urlShorteningAccessProvider.map(_()).getOrElse(throw new RuntimeException("no url shortener access provided to container"))

  //this container is used by tests only.
  // so far mockable apis (MockableLedgerAccess or MockableWalletAccess apis)
  // are synchronous and hence below implementation of 'runAsyncOp' is different than
  // what it might be in production code (like 'ActorProtocolContainer')
  override def runAsyncOp(op: => Any): Unit = {
    val result = Try(op)
    executeCallbackHandler(result)
  }

  def removeSegment(segmentKey: SegmentKey): SegmentKey = {
    // this is a hack to delete segment without directly invoking the api
    // (as that causes it to be in non stable state)
    val segmentId = segmentStoreStrategy_!.calcSegmentId(segmentKey)
    val domainId = Try(getDomainId).getOrElse(withShadowAndRecord(getDomainId))
    val address = segmentStoreStrategy_!.calcSegmentAddress(domainId, _storageId_!, segmentId, getProtoRef)
    system.removeSegment(address, segmentKey)
    segmentCache -= segmentKey
    segmentKey
  }
}

trait Logs {
  val logger: Logger = Logger(this.getClass)
}

class MockStorageService(system: SimpleProtocolSystem) extends SegmentStoreAccess {
  var S3Mock: Map[String, Array[Byte]] = Map()
  val MAX_SEGMENT_SIZE = 400000   //TODO: finalize this
  def isLessThanMaxSegmentSize(data: GeneratedMessage): Boolean = data.serializedSize < MAX_SEGMENT_SIZE

  def storeSegment(segmentAddress: SegmentAddress,
                   segmentKey: SegmentKey,
                   segment: Any,
                   retentionPolicy: Option[String]=None)
                  (handler: Try[StoredSegment] => Unit): Unit = {
    segment match {
      case msg: GeneratedMessage =>
        if (isLessThanMaxSegmentSize(msg)) {
          system.storeSegment(segmentAddress, segmentKey, msg)
        } else {
          //TODO: Add data retention to segmentKey
          S3Mock += segmentKey -> msg.toByteArray
        }
      case other =>
        system.storeSegment(segmentAddress, segmentKey, other)
    }
    handler(Try(StoredSegment(segmentAddress, segmentKey, Option(segment))))
  }

  def withSegment[T](segmentAddress: SegmentAddress,
                     segmentKey: SegmentKey,
                     retentionPolicy: Option[String]=None)
                    (handler: Try[Option[T]] => Unit): Unit = {
    val data = system.getSegment(segmentAddress, segmentKey) orElse S3Mock.get(segmentKey)
    handler(Try(data.map(_.asInstanceOf[T])))
  }

  def removeSegment(segmentAddress: SegmentAddress,
                    segmentKey: SegmentKey,
                    retentionPolicy: Option[String]=None)
                   (handler: Try[SegmentKey] => Unit): Unit = {
    handler(Try(system.removeSegment(segmentAddress, segmentKey)))
  }
}

trait HasInbox[A,B] {
  protected def inbox: BoxLike[A,B]
}

class ProtocolInstanceNotFound extends RuntimeException("Protocol instance not found")

trait InitProvider {
  def request(c: InMemoryProtocolContainer[_,_,_,_,_,_]): Unit
}

class RelationshipInitProvider(relationship: Relationship) extends InitProvider {
  def request(c: InMemoryProtocolContainer[_,_,_,_,_,_]): Unit = {
    val params = relationship.initParams(c.definition.initParamNames)
    c.submit(c.definition.createInitMsg(params))
  }
}

