package com.evernym.verity.protocol.engine

import com.evernym.verity.ServiceEndpoint
import com.evernym.verity.actor.agent.relationship.Relationship
import com.evernym.verity.protocol.actor.ServiceDecorator
import com.evernym.verity.protocol.engine.external_api_access.{LedgerAccess, LedgerAccessController, WalletAccess, WalletAccessController}
import com.evernym.verity.protocol.engine.journal.JournalContext
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.{Read, ReadStorage, Write, WriteStorage}
import com.evernym.verity.protocol.engine.segmentedstate.{SegmentStoreStrategy, SegmentedStateMsg}
import com.evernym.verity.protocol.engine.urlShortening.UrlShorteningAccess
import com.typesafe.scalalogging.Logger
import scalapb.GeneratedMessage

import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

case class ProtocolContainerElements[P,R,M,E,S,I](system: SimpleProtocolSystem,
                                                  participantId: ParticipantId,
                                                  pinstId: PinstId,
                                                  threadId: Option[ThreadId],
                                                  definition: ProtocolDefinition[P,R,M,E,S,I],
                                                  segmentStoreStrategy: Option[SegmentStoreStrategy],
                                                  initProvider: InitProvider,
                                                  eventRecorder: Option[RecordsEvents]=None,
                                                  driver: Option[Driver]=None,
                                                  parentLogContext: JournalContext=JournalContext(),
                                                  walletAccessProvider: Option[()=>WalletAccess] = None,
                                                  ledgerAccessProvider: Option[()=>LedgerAccess] = None )

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
    val segmentStoreStrategy = pce.segmentStoreStrategy
    val driver = pce.driver
    val participantId = pce.participantId
    val system = pce.system
    override val threadId = pce.threadId
    val initProvider = pce.initProvider
  } with ProtocolContainer[P,R,M,E,S,I]
    with HasInbox[Any,Any] {

  override val _threadId: Option[ThreadId] = pce.threadId
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
  val storageService: StorageService = new StorageService {
    var S3Mock: Map[String, Array[Byte]] = Map()
    def read(id: VerKey, cb: Try[Array[Byte]] => Unit): Unit = {
      S3Mock.get(id) match {
        case Some(x) => cb(Success(x))
        case None => cb(Failure(throw new Exception))
      }
    }

    def write(id: VerKey, data: Array[Byte], cb: Try[Any] => Unit): Unit =
      S3Mock += (id -> data)
  }

  def handleSegmentedMsgs(msg: SegmentedStateMsg, postExecution: Either[Any, Option[Any]] => Unit): Unit = {
    val resp = msg match {
      case Write(segmentAddress, segmentKey, value: GeneratedMessage) =>
        if (maxSegmentSize(value)) system.storeSegment(segmentAddress, segmentKey, value)
        else handleSegmentedMsgs(WriteStorage(segmentAddress, segmentKey, value), postExecution)
        None
      case WriteStorage(_, segmentKey, value) =>
        storageService.write(segmentKey, value.asInstanceOf[Array[Byte]], {
          case Success(_) =>
          case Failure(e) => throw e
        })
        None
      case Write(segmentAddress, segmentKey, value) =>
        system.storeSegment(segmentAddress, segmentKey, value); None
      case Read(segmentAddress, segmentKey) =>
        system.getSegment(segmentAddress, segmentKey)
      case ReadStorage(_, segmentKey, _) =>
        var data: Option[Any] = None
        storageService.read(segmentKey, {
          case Success(d) =>  data = Some(d)
          case Failure(e) => throw e
        })
        data
    }
    postExecution(Right(resp))
  }

  def registerWithSystem(): Unit = pce.system.register(this)

  override def createServices: Option[Services] = None

  def requestInit(): Unit = pce.initProvider.request(this)

  override lazy val wallet: WalletAccess = new WalletAccessController(
    grantedAccessRights,
    pce.walletAccessProvider.map(_()).getOrElse(throw new RuntimeException("no wallet access provided to container"))
  )

  override lazy val ledger: LedgerAccess = new LedgerAccessController(
    grantedAccessRights,
    pce.ledgerAccessProvider.map(_()).getOrElse(throw new RuntimeException("no ledger requests access provided to container"))
  )

  override def serviceEndpoint: ServiceEndpoint = s"http://www.example.com/$participantId"

  override def addToMsgQueue(msg: Any): Unit = {
    system.submit(msg, participantId)
  }

  registerWithSystem()

  override def urlShortening: UrlShorteningAccess = ???
}

trait Logs {
  val logger: Logger = Logger(this.getClass)
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

