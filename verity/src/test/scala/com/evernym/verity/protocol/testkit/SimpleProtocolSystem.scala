package com.evernym.verity.protocol.testkit

import com.evernym.verity.actor.agent.relationship.{DidDoc, Relationship, RelationshipName, SelfRelationship}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.did.DidStr
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.endorser.EndorserAccess
import com.evernym.verity.util2.HasExecutionContextProvider
import com.evernym.verity.protocol.engine.asyncapi.ledger.LedgerAccess
import com.evernym.verity.protocol.engine.asyncapi.urlShorter.UrlShorteningAccess
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.protocol.engine.journal.{JournalContext, JournalLogging, JournalProtocolSupport, Tag}
import com.evernym.verity.protocol.engine.registry.{LaunchesProtocol, ProtocolRegistry}
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.{SegmentAddress, SegmentKey}
import com.evernym.verity.protocol.{Control, CtlEnvelope}
import com.evernym.verity.util.MsgUtil
import com.typesafe.scalalogging.Logger

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag


case class DidRouter(routes: Map[DidStr,Domain] = Map.empty) {

  def -(did: DidStr): DidRouter = copy(routes - did)
  def +(route: (DidStr, Domain)): DidRouter = withRoute(route)
  def get(did: DidStr): Domain = routes.getOrElse(did, throw new RuntimeException(s"route not found for DID $did"))

  def withRoute(route: (DidStr,Domain)): DidRouter = {
    val (did, domain) = route
    withRoute(did, domain)
  }

  def withRoute(did: DidStr, domain: Domain): DidRouter = {
    routes.get(did) match {
      case None => copy(routes = routes + (did -> domain))
      case Some(d) if d == domain => this // did already registered to domain, so no change
      case _ => throw new RuntimeException(s"""DID "$did" already registered to another domain""")
    }
  }
}

trait SegmentedStateStore {
  def storeSegment(segmentAddress: SegmentAddress, segmentKey: SegmentKey, value: Any): Unit
  def getSegment(segmentAddress: SegmentAddress, segmentKey: SegmentKey): Option[Any]

  /**
    * This is for test protocol system so that we can accommodate lots of segment addresses into one map
    * @param segmentAddress - segment address where segment should be stored
    * @param segmentKey - segment key of the segment to be stored/retrieved
    * @return a unique address to be used for store/lookup
    */
  def buildUniqueKey(segmentAddress: SegmentAddress, segmentKey: SegmentKey): String = {
    segmentAddress + segmentKey
  }
}

class SimpleProtocolSystem() extends HasContainers with HasDidRouter with SegmentedStateStore {

  var domains: Vector[Domain] = Vector.empty

  def addDomain(d: Domain): Unit = domains = domains :+ d

  var segmentedState: Map[String, Any] = Map.empty

  def storeSegment(segmentAddress: SegmentAddress, segmentKey: SegmentKey, value: Any): Unit = {
    val uniqueSegmentId = buildUniqueKey(segmentAddress, segmentKey)
    segmentedState = segmentedState + (uniqueSegmentId -> value)
  }

  def getSegment(segmentAddress: SegmentAddress, segmentKey: SegmentKey): Option[Any] = {
    val uniqueSegmentId = buildUniqueKey(segmentAddress, segmentKey)
    segmentedState.get(uniqueSegmentId)
  }

  def removeSegment(segmentAddress: SegmentAddress, segmentKey: SegmentKey): SegmentKey = {
    val uniqueSegmentId = buildUniqueKey(segmentAddress, segmentKey)
    segmentedState -= uniqueSegmentId
    segmentKey
  }

  def totalStoredSegments: Int = segmentedState.size

  def handleControl[A <: Control](env: CtlEnvelope[A], myDid: DidStr): Unit = {
    val domain = didRouter.get(myDid)
    domain.handleControl(env, myDid: DidStr)
  }

  def handleOutMsg[A](env: Envelope1[A]): Unit = {
    val domain = didRouter.get(env.to)
    domain.submit(env)
  }

  @tailrec
  final def processAll(): Unit = {
    def processOne(c: InMemoryProtocolContainer[_,_,_,_,_,_]): Int = {
      c.processAllBoxes()
    }
    if (containers.map(processOne).sum > 0) {
      processAll()
    }
  }

  def start(): Unit = {
    containers.foreach(_.recoverOrInit())
    processAll()
  }

  def submit(msg: Any, partiId: ParticipantId): Unit = {
    containerForParti_!(partiId).submit(msg)
  }

  def initProvider(rel: Relationship, initValues: Map[String, String] = Map.empty): InitProvider =
    new RelationshipInitProvider(rel)
}

trait HasContainers {

  this: SimpleProtocolSystem =>

  type Container = InMemoryProtocolContainer[_,_,_,_,_,_]

  protected var containers: Set[Container] = Set.empty

  def getContainers: Set[Container] = containers

  def register(c: Container): Unit = {
    containers = containers + c
  }

  def deregister(c: Container): Unit = {
    containers = containers - c
  }

  def containerForParti(partiId: ParticipantId): Option[Container] = containerFor(_.participantId == partiId)
  def containerForPinstId(pid: PinstId): Option[Container] = containerFor(_.pinstId == pid)
  def containerForParti_!(partiId: ParticipantId): Container = containerFor_!(_.participantId == partiId)
  def containerForPinstId_!(pid: PinstId): Container = containerFor_!(_.pinstId == pid)

  private def containerFor(cond: Container => Boolean): Option[Container] = {
    val filtered = containers.filter(cond)
    filtered.size match {
      case 1 => Some(filtered.head)
      case 0 => None
      case _ => throw new RuntimeException("multiple containers found")
    }
  }

  private def containerFor_!(cond: Container => Boolean): Container = {
    containerFor(cond) getOrElse {
      throw new ContainerNotFoundException
    }
  }

}

class ContainerNotFoundException(msg: String = "container not found", cause: Throwable=null) extends RuntimeException(msg, cause)


trait HasDidRouter {

  var didRouter: DidRouter = DidRouter()

  def addRoute(did: DidStr, domain: Domain): Unit = {
    didRouter = didRouter + (did -> domain)
  }

  def removeRoute(did: DidStr): Unit = {
    didRouter = didRouter - did
  }

  def lookupRoute(did: DidStr): Domain = {
    didRouter.get(did: DidStr)
  }

}


class Domain(override val domainId: DomainId,
             override val protocolRegistry: ProtocolRegistry[SimpleControllerProviderInputType],
             val system: SimpleProtocolSystem,
             val executionContext: ExecutionContext,
             val appConfig: AppConfig,
             val defaultInitParams: Map[String, String] = Map.empty
            ) extends JournalLogging with JournalProtocolSupport with HasRelationships with SimpleLaunchesProtocol  {

  override def futureExecutionContext: ExecutionContext = executionContext

  type Container = InMemoryProtocolContainer[_,_,_,_,_,_]

  def containerProvider[P,R,M,E,S,I](pce: ProtocolContainerElements[P,R,M,E,S,I])(implicit ct: ClassTag[M]): Container = {
    new InMemoryProtocolContainer(pce, executionContext, appConfig)
  }

  var usedWalletAccess: Option[WalletAccess] = None
  def walletAccess(w: WalletAccess) : Unit = usedWalletAccess = Some(w)
  def provideWalletAccess(): WalletAccess = usedWalletAccess.getOrElse(throw new RuntimeException("no wallet access provided to container"))
  override def walletAccessProvider: Option[() => WalletAccess] = Some(provideWalletAccess _)

  var usedEndorserAccess: Option[EndorserAccess] = None
  def endorserAccess(e: EndorserAccess) : Unit = usedEndorserAccess = Some(e)
  def provideEndorserAccess(): EndorserAccess = usedEndorserAccess.getOrElse(throw new RuntimeException("no endorser access provided to container"))
  override def endorserAccessProvider: Option[() => EndorserAccess] = Some(provideEndorserAccess _)

  var usedLedgerAccess: Option[LedgerAccess] = None
  def ledgerAccess(w: LedgerAccess) : Unit = usedLedgerAccess = Some(w)
  def provideLedgerAccess(): LedgerAccess = usedLedgerAccess.get
  override def ledgerAccessProvider: Option[() => LedgerAccess] = Some(provideLedgerAccess _)

  var usedUrlShorteningAccess: Option[UrlShorteningAccess] = None
  def urlShorteningAccess(url: UrlShorteningAccess) : Unit = usedUrlShorteningAccess = Some(url)
  def provideUrlShorteningAccess(): UrlShorteningAccess = usedUrlShorteningAccess.get
  override def urlShorteningAccessProvider: Option[() => UrlShorteningAccess] = Some(provideUrlShorteningAccess _)


  var usedInitParams: Option[Map[String, String]] = None

  override def provideInitParams: Map[String, String] = defaultInitParams ++ usedInitParams.getOrElse(Map.empty)

  def initParams(w: Map[String, String]) : Unit = {
    usedInitParams = Some(w)
  }

  //only needed if protocol def is using V01 pinst resolver
  override def contextualId: Option[String] = Option(domainId)

  def startSoloInteraction(ctl: Control): Unit = {
    startInteraction(domainId, ctl)
  }

  def startInteraction(did: DidStr, ctl: Control): ThreadId = {
    val rel = lookup_!(did)
    startInteractionRel(rel, ctl)
  }

  // TODO make DID a proper type, and not an alias. This should be done because
  //  it doesn't provide type safety among other Strings, and functional
  //  overloading doesn't work properly because everything can be implicitly
  //  converted to a string.
  def startInteractionRel(rel: Relationship, ctl: Control): ThreadId = {
    val ctlEnvelope = MsgUtil.encloseCtl(ctl)
    val container = containerFor(ctl, ctlEnvelope.threadId, rel)
    handleControlRel(ctlEnvelope, rel)
    ctlEnvelope.threadId
  }

  val logger: Logger = Logger("Domain")
  val journalContext: JournalContext = JournalContext(domainId)

  def submit[A](e: Envelope1[A]): Unit = {
    containerFor(e).submit(e)
  }

  def containerFor[A](e: Envelope1[A]): Container = {
    val rel = lookup_!(e.to)
    containerFor(e, rel)
  }

  def handleControl[A <: Control](cenv: CtlEnvelope[A], myDid: DidStr): Unit = {
    handleControlRel(cenv, lookup_!(myDid))
  }

  def handleControlRel[A <: Control](cenv: CtlEnvelope[A], rel: Relationship): Unit = {
    withLog("handling control", cenv) {
      containerFor(cenv, rel).submit(cenv)
    }
  }

  withLog("create domain") {
    addSelfRelationship(domainId)
    record( "added self relationship")
    system.addDomain(this)
    record("added domain to system")
  }

}

trait HasRelationships {
  this: Domain =>

  def system: SimpleProtocolSystem

  private var relationships: Map[RelationshipName, Relationship] = Map.empty
  //  protected var defaultRelationship: Relationship = NoRelationship

  def addRelationship(rel: Relationship): Unit = {
    relationships = relationships + (rel.name -> rel)
    system.addRoute(rel.myDid_!, this)
  }

  def addSelfRelationship(did: DidStr): Relationship = {
    val rel = SelfRelationship(Some(DidDoc(did)))
    addRelationship(rel)
    rel
  }

  //had to add this to be able to fix dead drop spec
  def removeRelationship(did: DidStr): Unit = {
    relationships = relationships - did
  }

  /**
    * Allows to look up a relationship by DID (this DID or that DID)
    */
  def lookup(did: DidStr): Option[Relationship] = {
    val filt = relationships.values.filter(r => r.myDid_! == did || r.theirDid.contains(did))
    filt.size match {
      case 0 => None
      case 1 => Some(filt.head)
      case _ => throw new RuntimeException(s"more than one relationship has the same DID $did")
    }
  }

  def lookup_!(did: DidStr): Relationship = {
    lookup(did).getOrElse(throw new RuntimeException(s"relationship with DID $did not found"))
  }
}


trait SimpleLaunchesProtocol extends LaunchesProtocol with HasExecutionContextProvider {

  self: JournalLogging =>

  type Container <: InMemoryProtocolContainer[_,_,_,_,_,_]// = InMemoryProtocolContainer[_,_,_,_,_,_]
  type ControllerProviderInputType = SimpleControllerProviderInputType

  def containerProvider[P,R,M,E,S,I](pce: ProtocolContainerElements[P,R,M,E,S,I])(implicit ct: ClassTag[M]): Container

  def system: SimpleProtocolSystem

  def walletAccessProvider: Option[() => WalletAccess] = None

  def ledgerAccessProvider: Option[() => LedgerAccess] = None

  def endorserAccessProvider: Option[() => EndorserAccess] = None

  def urlShorteningAccessProvider: Option[() => UrlShorteningAccess] = None

  def provideInitParams: Map[String, String] = Map.empty

  def containerFor[A <: Control](c: CtlEnvelope[A], rel: Relationship): Container = {
    containerFor(c.msg, c.threadId, rel)
  }

  def containerFor[A](e: Envelope1[A], rel: Relationship): Container = {
    val tid = e.threadId getOrElse { throw new RuntimeException("cannot access container; this system requires thread ids")}
    containerFor(e.msg, tid, rel)
  }

  def containerFor[A](msg: A, threadId: ThreadId, rel: Relationship): Container = {
    val pip = pinstIdForUntypedMsg_!(msg, rel.myDid, threadId)
    record("calculating pinst id", (pip.id, (msg, rel.myDid, threadId)))

    // treating containers as a map of pinstid -> container for now
    system.containerForPinstId(pip.id)
    .map(_.asInstanceOf[Container])
    .getOrElse {
      createContainer(pip.id, threadId, rel, pip.protoDef)
    }
  }

  private def createContainer(pinstId: PinstId, threadId: ThreadId, rel: Relationship, protoDef: ProtoDef): Container = {
    withLog("create container", (pinstId.take(5), threadId), Tag.magenta) {

      val initProvider = system.initProvider(rel, provideInitParams)

      val driverParam = SimpleControllerProviderInputType(system, rel.myDid_!, threadId)

      val driver = protocolRegistry.find_!(protoDef.protoRef).driverGen map { _.apply(driverParam, futureExecutionContext) }

      val pce = ProtocolContainerElements( system, rel.myDid_!, pinstId, Option(threadId), protoDef,
        initProvider, None, driver, journalContext, walletAccessProvider, ledgerAccessProvider,
        endorserAccessProvider, urlShorteningAccessProvider)

      val container = containerProvider(pce)

      system.register(container)

      container.recoverOrInit() //TODO add log msg here

      container
    }
  }

}

case class SimpleControllerProviderInputType(system: SimpleProtocolSystem, myDid: DidStr, threadId: ThreadId)
