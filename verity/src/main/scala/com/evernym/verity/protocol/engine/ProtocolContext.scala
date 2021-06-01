package com.evernym.verity.protocol.engine

import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.actor.agent.SpanUtil.runWithInternalSpan
import com.evernym.verity.actor.agent.TypeFormat.STANDARD_TYPE_FORMAT
import com.evernym.verity.actor.agent._
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.getNewMsgUniqueId
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.evernym.verity.protocol._
import com.evernym.verity.protocol.container.actor.Init
import com.evernym.verity.protocol.engine.asyncapi.segmentstorage.SegmentStoreAccess
import com.evernym.verity.protocol.engine.asyncapi.{AccessRight, AsyncOpRunner}
import com.evernym.verity.protocol.engine.journal.{JournalContext, JournalLogging, JournalProtocolSupport, Tag}
import com.evernym.verity.protocol.engine.msg.{SetDataRetentionPolicy, SetDomainId, SetSponsorRel, SetStorageId, PersistenceFailure, UpdateThreadContext}
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateContext
import com.evernym.verity.protocol.engine.util.{?=>, marker}
import com.evernym.verity.protocol.legacy.services.ProtocolServices
import com.github.ghik.silencer.silent
import com.typesafe.scalalogging.Logger
import org.slf4j.Marker

import scala.concurrent.Future
import scala.util.Try

/**
  * Generically holds and manages protocol state.
  *
  * Today, we have a base protocol class that all protocols extend from.
  * We are changing that in favor of protocols implementing only a
  * structural type. The protocol base functionality needs to be moved
  * into this trait.
  *
  */
trait ProtocolContext[P,R,M,E,S,I]
  extends ProtocolContextApi[P,R,M,E,S,I]
    with SegmentedStateContext[P,R,M,E,S,I]
    with JournalLogging
    with JournalProtocolSupport
    with AsyncOpRunner {

  def pinstId: PinstId

  def _threadId: Option[ThreadId] = getInFlight.threadId
  def _threadId_! : ThreadId = _threadId getOrElse { throw new RuntimeException("thread id is required") }
  def _storageId: Option[StorageId] = getBackState.storageId
  def _storageId_! : StorageId = _storageId getOrElse { throw new RuntimeException("storage id is required") }


  lazy val logger: Logger = getLoggerByName(s"${definition.msgFamily.protoRef.toString}")
  lazy val journalContext: JournalContext = JournalContext(pinstId.take(5))

  override lazy val logMarker: Option[Marker] = Some(marker.protocol)

  def definition: ProtocolDefinition[P,R,M,E,S,I]

  def protocol: Protocol[P,R,M,E,S,I]

  def driver: Option[Driver]

  def eventRecorder: RecordsEvents

  def segmentStore: SegmentStoreAccess

  def sendsMsgs: SendsMsgs

  def sendSMS(toPhoneNumber: String, msg: String): Future[String] = sendsMsgs.sendSMS(toPhoneNumber, msg)

  def grantedAccessRights: Set[AccessRight] = protocol.definition.requiredAccess

  /* This is set during initialization of the protocol. The policy will not change throughout the protocol.*/
  def dataRetentionPolicy: Option[String] = backState.dataRetentionPolicy

  @deprecated("Use of services is deprecated. Use the instance of " +
    "ProtocolContextApi to access driver and the ability to send " +
    "messages.", PROTOCOL_ENCAPSULATION_FIX_DATE)
  def _services: Option[ProtocolServices[M,E,I]]

  var state: S = definition.initialState
  var backState: BackState = BackState()

  /**
    * shadow state objects used to ensure atomicity of message handling
    */
  private var shadowState: Option[S] = None
  private var shadowBackState: Option[BackState] = None

  private def readyToProcessInbox: Boolean = shadowState.isEmpty && inbox.nonEmpty

  /**
    * pending events used to ensure atomicity of message handling
    *
    * multiple events can be applied to pendingEvents during a specific transaction
    * but during actual persistence, the events are grouped into one macro event and only it is persisted (i.e 1 event)
    */
  var pendingEvents: Vector[_ >: E with ProtoSystemEvent] = Vector()

  /**
    * accessors for shadow state
    */
  def getRoster: Roster[R] = getBackState.roster
  def getState: S = shadowState.getOrElse(throw new IllegalStateAccess("state"))
  def getBackState: BackState = shadowBackState.getOrElse(throw new IllegalStateAccess("backState"))

  /**
   * any in-flight, ephemeral (non-persisted) state
   */
  private val participantMsg = new PartiMsg
  private var inFlight: Option[InFlight] = None
  def getInFlight: InFlight = inFlight.getOrElse(throw new IllegalStateAccess("in-flight"))

  def apply[A >: E with ProtoSystemEvent](event: A): Unit = {
    record("applying event", event)
    applyToShadow(event)
    pendingEvents = pendingEvents :+ event
  }

  @silent // TODO we should fix this typing, erasure make the type checking ineffective
  def applyToShadow[A >: E with ProtoSystemEvent](event: A): Unit = {
    event match {
      case me: MultiEvent => me.evts.foreach(applyToShadow)
      case pse: ProtoSystemEvent => shadowBackState = Option(applySystemEvent(pse))
      case e: E =>
        val result = try {
          protocol.applyEvent(getState, getRoster, e)
        } catch {
          case me: MatchError =>
            throw new NoEventHandler(state.toString, getRoster.selfRole.map(_.toString).getOrElse(""), event.toString)
        }
        val sct = Option(result).getOrElse(throw new InvalidState("applyEvent"))
        sct._1.foreach { newState => shadowState = Option(newState) }
        sct._2.foreach { newRoster => shadowBackState = Some(getBackState.copy(roster = newRoster)) }
    }
  }

  def setupInflightMsg[A](msgId: Option[MsgId], threadId: Option[ThreadId],
                          sender: SenderLike[R])(f: => A): A = {
    inFlight = inFlight.map(_.copy(msgId = msgId, threadId = threadId)).orElse(
    Some(InFlight(msgId, threadId, sender)))
    f
  }

  //TODO can we make this private?
  protected lazy val inbox: BoxLike[Any,Any] =
    new Box("inbox", "control or protocol message", handleMsg(_), journalContext)

  protected lazy val outbox: BoxLike[ProtocolOutgoingMsg,Unit] =
    new Box("outbox", "protocol message", sendsMsgs.send, journalContext)

  protected lazy val signalOutbox = new SignalOutbox(driver, inbox, journalContext)

  lazy val allBoxes: Vector[BoxLike[_,_]] = Vector(inbox, outbox, signalOutbox)

  /**
    * Containers call this to submit a message to the protocol. Uses an inbox
    * and includes conditional processing of that inbox and other boxes that might be
    * populated as a result. Process only ONE inbox message.
    */
  def submit[A](msg: A, callback: Option[Try[Any] => Unit] = None): Unit = {
    // flow diagram: ctl + proto, step 18
    inbox.add(msg, callback)
    processNextInboxMsg()
  }

  protected def processNextInboxMsg(): Unit = {
    // flow diagram: ctl + proto, step 19
    if (readyToProcessInbox) inbox.processNext()
  }


  /**
    * outboxes are processed once event persistence completes (onPersistSuccess/onPersistFailure)
    * the persistence of one inbox message will trigger the processing of all outbox messages
    */
  def processOutputBoxes(): Unit = {
    outbox.process()
    signalOutbox.process()
  }

  @scala.annotation.tailrec
  final def processAllBoxes(tally: Int = 0): Int = {
    val processed = allBoxes.flatMap(_.process()).size
    val newTally = tally + processed
    if (processed == 0) newTally else processAllBoxes(newTally)
  }

  def handleMsg: Any ?=> Any = {
    case m => handleMsgBase(m)
  }

  @silent // TODO we should fix this typing, erasure make the type checking ineffective
  private def handleMsgBase: Any ?=> Any = {

    case Envelope1(msg: M, to, frm, msgId, tid) =>
      //TODO deal with tid (threadId)
      runWithInternalSpan("proto-msg:" + msg.getClass.getSimpleName, "ProtocolContext") {
        withShadowAndRecord {

          participantMsg.add(to, msgId)

          val sender = getRoster.senderFromId(frm)
          setupInflightMsg(msgId, tid, sender) {
            withLog("handle protocol message", msg, Tag.red) {
              try {
                checkIfProtocolMsg(msg)
                incrementReceivedOrder(frm)
                protocol.handleProtoMsg(getState, sender.role, msg)
              } catch {
                case me: MatchError =>
                  abortTransaction()
                  throw new NoProtocolMsgHandler(
                    state.getClass.getSimpleName,
                    sender.role.map(_.toString).getOrElse("UNKNOWN"),
                    msg.getClass.getSimpleName
                  )
              }
            }
          }
        }
      }

    case cenv: CtlEnvelope[_] =>
      runWithInternalSpan("control-msg:" + cenv.msg.getClass.getSimpleName, "ProtocolContext") {
        withShadowAndRecord {
          val sender = getRoster.selfSender
          setupInflightMsg(Option(cenv.msgId), Option(cenv.threadId), sender) {
            handleControl(cenv.msg)
          }
        }
      }

    //TODO this one is needed for Init... maybe we can wrap init in a CtlEnvelope
    case ctl: Control =>
      runWithInternalSpan("control-msg:" + ctl.getClass.getSimpleName, "ProtocolContext") {
        withShadowAndRecord {
          setupInflightMsg(None, None, getRoster.selfSender) {
            handleControl(ctl)
          }
        }
      }

    case sys: SystemMsg =>
      runWithInternalSpan("system-msg:" + sys.getClass.getSimpleName, "ProtocolContext") {
        val id = Some(getNewMsgUniqueId)
        withShadowAndRecord {
          sys match {
            case in: InternalSystemMsg =>
              handleInternalSystemMsg(in)
            case normal =>
              setupInflightMsg(id, id, getRoster.selfSender) {
                handleSystemMsg(normal)
              }
          }
        }
      }
  }

  def handleControl(ctl: Control): Any = {
    withLog("handle control", ctl) {
      try {
        checkIfControlMsg(ctl)
        protocol.handleControl(ctl)
      } catch {
        case _: MatchError =>
          throw new NoControlHandler(ctl.getClass.getSimpleName, state.getClass.getSimpleName)
      }
    }
  }

  protected def applySystemEvent: ProtoSystemEvent ?=> BackState = {
    case PinstIdSet(_) => shadowBackState.getOrElse(BackState()) // kept it for backward compatibility

    case DomainIdSet(id) =>
      shadowBackState.getOrElse(BackState()).copy(domainId = Option(id))

    case StorageIdSet(id) =>
      shadowBackState.getOrElse(BackState()).copy(storageId = Option(id))

    case PairwiseRelIdsChanged(self, other) =>
      val s = shadowBackState.getOrElse(BackState())
      val newRoster = s.roster.changeSelfId(self).changeOtherId(other)
      s.copy(roster = newRoster)

    case s: SponsorRel => BackState()
      shadowBackState.getOrElse(BackState()).copy(sponsorRel = Option(s))

    case p: DataRetentionPolicySet => BackState()
      shadowBackState.getOrElse(BackState()).copy(dataRetentionPolicy = Option(p.policy))

    case pcs: PackagingContextSet =>
      val pc = PackagingContext.init(pcs)
      shadowBackState.getOrElse(BackState()).copy(packagingContext = Option(pc))

    case lpcs: LegacyPackagingContextSet =>
      val pc = shadowBackState.getOrElse(BackState()).packagingContext
      val updatedPc = pc.map(_.updateLegacyPackagingContext(lpcs))
      shadowBackState.getOrElse(BackState()).copy(packagingContext = updatedPc)

    case ros: ReceivedOrdersSet =>
      val mrod = shadowBackState.getOrElse(BackState()).msgOrders.getOrElse(MsgOrders(senderOrder = -1))
      val updated = mrod.copy(receivedOrders = ros.receivedOrders)
      shadowBackState.getOrElse(BackState()).copy(msgOrders = Option(updated))

    case sos: SenderOrderSet =>
      val mrod = shadowBackState.getOrElse(BackState()).msgOrders.getOrElse(MsgOrders(senderOrder = -1))
      val updated = mrod.copy(senderOrder = sos.order)
      shadowBackState.getOrElse(BackState()).copy(msgOrders = Option(updated))

    case mroi: ReceivedOrderIncremented =>
      val mrod = shadowBackState.getOrElse(BackState()).msgOrders.getOrElse(MsgOrders(senderOrder = -1))
      val curValue = mrod.receivedOrders.getOrElse(mroi.fromPartiId, -1)
      val updated = mrod.copy(receivedOrders = mrod.receivedOrders ++ Map(mroi.fromPartiId -> (curValue + 1)))
      shadowBackState.getOrElse(BackState()).copy(msgOrders = Option(updated))

    case _: SenderOrderIncremented =>
      val mrod = shadowBackState.getOrElse(BackState()).msgOrders.getOrElse(MsgOrders(senderOrder = -1))
      val updated = mrod.copy(senderOrder = mrod.senderOrder + 1)
      shadowBackState.getOrElse(BackState()).copy(msgOrders = Option(updated))
  }

  def handleInternalSystemMsg(sysMsg: InternalSystemMsg): Any = {
    sysMsg match {
      case SetDomainId(id)              =>
        if (backState.domainId.isEmpty) apply(DomainIdSet(id))
      case SetStorageId(id)             =>
        if (backState.storageId.isEmpty) apply(StorageIdSet(id))
      case SetSponsorRel(s)             =>
        if (backState.sponsorRel.isEmpty) apply(s)
      case SetDataRetentionPolicy(p)    =>
        if (backState.dataRetentionPolicy.isEmpty) p.map(x => apply(DataRetentionPolicySet(x)))
      case utc: UpdateThreadContext     =>

        if (backState.packagingContext.isEmpty) {
          apply(PackagingContextSet(utc.pd.msgPackFormat.value))
          apply(LegacyPackagingContextSet(utc.pd.msgTypeDeclarationFormat.value,
            utc.pd.usesLegacyGenMsgWrapper, utc.pd.usesLegacyBundledMsgWrapper))
        }

        utc.senderOrder.foreach { updatedSenderOrder =>
          if (! backState.msgOrders.map(_.senderOrder).contains(updatedSenderOrder))
            apply(SenderOrderSet(updatedSenderOrder))
        }

        utc.receivedOrder.foreach { updatedReceivedOrder =>
          if (! backState.msgOrders.map(_.receivedOrders).contains(updatedReceivedOrder))
            apply(ReceivedOrdersSet(updatedReceivedOrder))
        }
    }
  }

  def handleSystemMsg(sysMsg: SystemMsg): Any = {
    withLog("handle system msg", sysMsg) {
      protocol.handleSystemMsg(sysMsg)
    }
  }

  /**
  * Refer to def finalizeState() documentation for async finalizeState
  */
  def withShadowAndRecord[A](f: => A): A = {
    try {
      constructShadow()
      val result = f
      postAllAsyncOpsCompleted()
      result
    } catch {
      case e: Exception => abortTransaction(); throw e
    }
  }

  /**
   * execute store segment, record events and finalizing state
   * once all in-progress async services (url-shortener, wallet, ledger and segmentStorage) is executed
   */
  def postAllAsyncOpsCompleted(): Unit = {
    if (isAllAsyncOpsCompleted) {
      recordEvents getOrElse finalizeState
    }
  }

  def abortTransaction(): Unit = {
    allBoxes.foreach(_.clear())
    pendingEvents = Vector()
    clearShadowState()
    resetAllAsyncOpCallBackHandlers()
    record("protocol context cleaned up")
  }

  /** Setting state and backstate is removed from 'withShadow'
    * and now it is set based on the context where this 'withShadow' is called
    * as it seems the caller knows when to do that instead of relying on 'withShadow'
    * @param event
    * @tparam A
    */
  def applyRecordedEvent[A >: E with ProtoSystemEvent](event: A): Unit = {
    constructShadow()
    applyToShadow(event)

    state = getState
    backState = getBackState

    clearShadowState()
  }

  /**
    * constructShadow follows a two part commit pattern. The processing of a message, constructing of the shadowState,
    * and the attempt at event persistence happens in constructShadow. The actual state transition happens once the event
    * is actually persisted (onPersistSuccess) or in the handling of a persistence error (onPersistFailure)
    */
  def constructShadow(): Unit = {
    if (shadowState.isDefined)
      throw new InvalidState("shadowState should be undefined when starting to handle a message")
    if (shadowBackState.isDefined)
      throw new InvalidState("shadowBackState should be undefined when starting to handle a message")

    shadowState = Some(state)
    shadowBackState = Some(backState)
    record("updated shadow state", state)
  }

  private def advanceStateVersion(): Unit = {
    shadowBackState = Some(getBackState.advanceVersion)
  }

  /**
    * There are three places that can call finalizeState
    * 1) Internal Protocol async services
    * 2) when storage/segment persistence is complete
    * 3) when event persistence is complete
    * readyToFinalize ensures that processes are complete
    */
  def finalizeState(): Unit = {
    if (readyToFinalize) {
      state = shadowState.getOrElse(state)
      backState = shadowBackState.getOrElse(backState)
      inFlight = None

      clearShadowState()
      resetAllAsyncOpCallBackHandlers()

      processOutputBoxes()
      processNextInboxMsg()
    }
  }

  /**
   * Finalization cannot happen until all protocol services are complete.
   * This includes:
   *  1. Protocol 'Internal Services' - url shortener, wallet, ledger and segmentedStore services
   *  2. 'Segmented State' storage
   *  3. 'Event Persistence'
   */
  def readyToFinalize: Boolean = isAllAsyncOpsCompleted && pendingEvents.isEmpty

  /**
   * This is called when the base 'onPersistFailure' or 'onPersistRejected' from akka persistence is invoked
   */
  def eventPersistenceFailure(cause: Throwable, event: Any): Unit = {
    logger.error(s"Protocol failed to persist event: ${event.getClass.getSimpleName} because: $cause")
    abortTransaction()
    inbox.add(PersistenceFailure(cause, event))
    processNextInboxMsg()
    processOutputBoxes()
  }

  /**
   * Akka Persistence takes a callback when persisting an event. When the event is successful, the callback is called.
   * This function is called within that callback.
   * When the persistence fails, 'onPersistFailure' or 'onPersistRejected' is called.
   */
  def eventPersistSuccess(event: Any): Unit = {
    logger.debug(s"successfully persisted event: $event")
    pendingEvents = Vector()
    finalizeState()
  }


  def handleNoEvent(): Unit = finalizeState()

  def clearShadowState(): Unit = {
    shadowState = None
    shadowBackState = None
  }

  /**
   * (If run in an actor) recordEvents blocks execution by inheriting akka persistence's event persistence blocking.
   */
  def recordEvents(): Option[_ >: E with ProtoSystemEvent] = {
    val event = pendingEvents.size match {
      case 0 => None
      case 1 => Some(pendingEvents.head)
      case _ => Some(MultiEvent(pendingEvents))
    }
    event foreach { e =>
      advanceStateVersion()
      withLog("record event", (pinstId, e)) {
        eventRecorder.record(pinstId, e, state, eventPersistSuccess)
      }
    }
    event
  }

  def senderPartiId(fromRole: Option[R]=None): ParticipantId = fromRole.map { _fromRole =>
    getRoster.participantIdForRole(_fromRole).getOrElse(throw new InvalidState(s"role $fromRole not assigned"))
  } getOrElse getRoster.selfId_!

  def recipPartiId(toRole: Option[R]=None): ParticipantId = toRole.map { _toRole =>
    getRoster.participantIdForRole(_toRole).getOrElse(throw new InvalidState(s"role $toRole not assigned"))
  } getOrElse getRoster.otherId()

  def msgId(fromPartiId: ParticipantId): Option[MsgId] =
  //TODO JL: why have two different ways of doing this?
    participantMsg.getMsgIdForPartiId(fromPartiId) orElse inFlight.flatMap(_.msgId)

  def prepareEnvelope[A](msg: A, toRole: Option[R]=None, fromRole: Option[R]=None): Envelope1[A] = {
    val fromPartiId = senderPartiId(fromRole)
    val mId = msgId(fromPartiId)
    Envelope1(msg, recipPartiId(toRole), fromPartiId, mId, inFlight.flatMap(_.threadId))
  }

  def send[T](msg: M, toRole: Option[R]=None, fromRole: Option[R]=None): Unit = {
    checkIfProtocolMsg(msg)
    incrementSenderOrder()
    val env = prepareEnvelope(msg, toRole, fromRole)
    withLog ("sending message to participant", env) {
      val pmsg = sendsMsgs.prepare(env)
      outbox.add(pmsg)
    }
  }

  def signal(signal: Any): Unit = {
    checkIfSignalMsg(signal)
    if (driver.isDefined) {
      val sm = SignalEnvelope(signal, definition.msgFamily.protoRef,
        pinstId, threadContextDetailReq, inFlight.flatMap(_.msgId))
      signalOutbox.add(sm)
    }
  }

  def threadContextDetailReq: ThreadContextDetail = {
    val packagingContext = getBackState.packagingContext.getOrElse(PackagingContext())
    val msgOrders = getBackState.msgOrders
    ThreadContextDetail(
      _threadId_!,
      packagingContext.msgPackFormat,
      packagingContext.msgTypeDeclarationFormat,
      packagingContext.usesLegacyGenMsgWrapper,
      packagingContext.usesLegacyBundledMsgWrapper,
      msgOrders
    )
  }

  def updatedRoster(params: Seq[InitParamBase]): Roster[R] = {

    /** Applies a single parameter to a provided Roster and returns a new Roster
      *
      * @param roster a provided Roster to which the param is applied
      * @param name the param name
      * @param value the param value to apply to the provided state
      * @return a new state that is the result of applying the parameter to the provided state
      */
    def applyParam(roster: Roster[R], name: String, value: ParticipantId): Roster[R] = {
      name match {
        case SELF_ID | OTHER_ID => roster.withParticipant(value, name == SELF_ID)
        case _ => roster// logger.debug(s"ignoring unsupported init parameter: $x"); roster
      }
    }

    params.foldLeft {
      getRoster
    }{ (r, p) =>
      applyParam(r, p.name, p.value)
    }
  }

  def checkIfControlMsg(msg: Any): Unit = {
    checkIfRegistered(msg, "control")
  }

  def checkIfProtocolMsg(msg: Any): Unit = {
    checkIfRegistered(msg, "protocol")
  }

  def checkIfSignalMsg(msg: Any): Unit = {
    checkIfRegistered(msg, "signal")
  }

  private def checkIfRegistered(msg: Any, msgCategory: String): Unit = {
    val result = msgCategory match {
      case "control"  =>
        classOf[Init] == msg.getClass || definition.msgFamily.isControlMsg(msg)
      case "signal"   => definition.msgFamily.isSignalMsg(msg)
      case "protocol" => definition.msgFamily.isProtocolMsg(msg)
    }
    if (! result) {
      throw new RuntimeException(s"'${msg.getClass.getSimpleName}' not registered as a '$msgCategory' message in ${definition.msgFamily.protoRef}")
    }
  }

  /**
   * stores packaging detail if it is not already stored/persisted
   * @param tc
   */
  def storePackagingDetail(tc: ThreadContextDetail): Unit = {
    if (backState.packagingContext.isEmpty) {
      val pc = PackagingContext(
        tc.msgPackFormat,
        tc.msgTypeFormat,
        tc.usesLegacyGenMsgWrapper,
        tc.usesLegacyBundledMsgWrapper
      )
      submit(
        UpdateThreadContext(pc,
          tc.msgOrders.map(_.senderOrder),
          tc.msgOrders.map(_.receivedOrders)))
    }
  }

  def incrementReceivedOrder(senderPartiId: ParticipantId): Unit = {
    apply(ReceivedOrderIncremented(senderPartiId))
  }

  def incrementSenderOrder(): Unit = {
    apply(SenderOrderIncremented())
  }

  case class BackState(roster: Roster[R]=Roster(),
                       stateVersion: Int=0,
                       domainId: Option[DomainId]=None,
                       packagingContext: Option[PackagingContext] = None,
                       msgOrders: Option[MsgOrders] = None,
                       sponsorRel: Option[SponsorRel]=None,
                       dataRetentionPolicy: Option[String]=None,
                       storageId: Option[StorageId]=None) {
    def advanceVersion: BackState = {
      this.copy(stateVersion = this.stateVersion + 1)
    }
  }

  case class InFlight(msgId: Option[MsgId], threadId: Option[ThreadId], sender: SenderLike[R])

  /**
   * protocol exceptions
   */
  class IllegalStateAccess(item: Any) extends RuntimeException(s"invalid $item access; are you referencing state outside of a message handler?")
  class InvalidState(msg: String) extends RuntimeException(msg)
}


/** participant id and msg id mapping
  * this is required when we break a msg processing into two parts and wants to reply when last part is done
  */
class PartiMsg {
  private var partiMsgIdMapping: Map[ParticipantId, MsgId] = Map.empty

  def add(to: ParticipantId, msgId: Option[MsgId]): Unit = {
    msgId.foreach { mId =>
      add(to -> mId)
    }
  }

  def add(pair: (ParticipantId, MsgId)): Unit = {
    partiMsgIdMapping = partiMsgIdMapping + pair
  }

  def remove(senderPartiId: ParticipantId): Unit = {
    partiMsgIdMapping = partiMsgIdMapping - senderPartiId
  }

  def getMsgIdForPartiId(senderPartiId: ParticipantId): Option[MsgId] = {
    partiMsgIdMapping.get(senderPartiId).map { m =>
      //TODO JL: why is this removed? Also, it's not obvious from the method name that it alters something.
      remove(senderPartiId)
      m
    }
  }
}

abstract class NoHandler(msg: String) extends RuntimeException(msg)

class NoControlHandler(ctl: String, state: String)
  extends NoHandler(s"no control handler found for control message `$ctl` in state `$state`")

class NoEventHandler(state: String, role: String, event: String)
  extends NoHandler(s"no event handler found for event `$event` in state `$state` with role `$role`")

class NoSystemMsgHandler(sysMsg: String, state: String)
  extends NoHandler(s"no system msg handler found for system message `$sysMsg` in state `$state`")

class NoProtocolMsgHandler(state: String, role: String, msg: String)
  extends NoHandler(s"no protocol msg handler found for message `$msg` in state `$state` with role `$role`")

object PackagingContext {
  def init(pcs: PackagingContextSet): PackagingContext =
    PackagingContext(MsgPackFormat.fromValue(pcs.msgPackFormat))
}

case class PackagingContext(msgPackFormat: MsgPackFormat = MPF_INDY_PACK,
                            msgTypeDeclarationFormat: TypeFormat = STANDARD_TYPE_FORMAT,
                            usesLegacyGenMsgWrapper: Boolean = false,
                            usesLegacyBundledMsgWrapper: Boolean = false) {

  def updateLegacyPackagingContext(lpcs: LegacyPackagingContextSet): PackagingContext =
    copy(
      msgTypeDeclarationFormat = TypeFormat.fromValue(lpcs.msgTypeDeclarationFormat),
      usesLegacyGenMsgWrapper = lpcs.usesLegacyGenMsgWrapper,
      usesLegacyBundledMsgWrapper = lpcs.usesLegacyBundledMsgWrapper
    )
}
