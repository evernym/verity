package com.evernym.verity.protocol.testkit

import com.evernym.verity.actor.agent.relationship.PairwiseRelationship
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.ProtocolRegistry.{DriverGen, Entry}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.util2.HasExecutionContextProvider
import com.evernym.verity.protocol.engine.asyncapi.ledger.LedgerAccess
import com.evernym.verity.protocol.engine.asyncapi.urlShorter.UrlShorteningAccess
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.protocol.engine.segmentedstate.SegmentStoreStrategy
import com.evernym.verity.protocol.protocols.HasAppConfig
import com.evernym.verity.protocol.testkit.InteractionType.{OneParty, TwoParty}
import com.evernym.verity.util.{MsgIdProvider, MsgUtil}
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

object DSL {

  class SignalClass[T](val ct: ClassTag[T])
  class StateClass[T](val ct: ClassTag[T])

  object signal {
    def apply[T](implicit ct: scala.reflect.ClassTag[T]) = new SignalClass(ct)
  }

  object state {
    def apply[T](implicit ct: scala.reflect.ClassTag[T]) = new StateClass(ct)
  }

  class SignalsWord
  val signals = new SignalsWord

}

class ProtocolTestKit[P,R,M,E,S,I](val protoDef: ProtocolDefinition[P,R,M,E,S,I],
                                   executionContext: ExecutionContext,
                                   ac: AppConfig,
                                   val segmentStoreStrategy: Option[SegmentStoreStrategy]=None)(implicit val mtag: ClassTag[M])
  extends ProtocolTestKitLike[P,R,M,E,S,I] {
  override def futureExecutionContext: ExecutionContext = executionContext
  override def appConfig: AppConfig = ac
}

trait ProtocolTestKitLike[P,R,M,E,S,I] extends HasExecutionContextProvider {

  tk =>

  type TestSystem = TestSimpleProtocolSystem

  def defaultInteractionType: InteractionType = TwoParty

  def defaultInitParams: Map[String, String] = Map.empty
  def appConfig: AppConfig

  val defaultPinstIdResolver: PinstIdResolver = PinstIdResolution.V0_2

  implicit val mtag: ClassTag[M]
  def protoDef: ProtocolDefinition[P,R,M,E,S,I]
  def segmentStoreStrategy: Option[SegmentStoreStrategy]
  type Container = InMemoryProtocolContainer[P,R,M,E,S,I]

  val defaultControllerProvider: DriverGen[SimpleControllerProviderInputType] =
    Some((a: SimpleControllerProviderInputType, b: ExecutionContext) => new InteractionController(a))

  def setup(name: String,
            odg: DriverGen[SimpleControllerProviderInputType]=None,
            it: InteractionType=defaultInteractionType
           )(implicit system: TestSystem): TestEnvir = {
    val dg = odg orElse defaultControllerProvider
    val protoReg = ProtocolRegistry[SimpleControllerProviderInputType](Entry(protoDef, defaultPinstIdResolver, dg))
    new TestEnvir(system, new Domain(name, protoReg, system, futureExecutionContext, appConfig, defaultInitParams), it)
  }

  def interaction(envirs: TestEnvir *): PlayDSL = {
    new PlayDSL (
      envirs map { e => (e, None) }
    )
  }

  type TestEnvirToDid = (TestEnvir, DID)
  type TestEnvirToOptDid = (TestEnvir, Option[DID])

  def playExt(pairs: TestEnvirToDid *): PlayDSL = {
    new PlayDSL (
      pairs map { p => (p._1, Option(p._2)) }
    )
  }

  class PlayDSL(val envirs: Seq[TestEnvirToOptDid]) {

    def apply[T](block: => T): T = {

//      println(s"-----------Starting interaction with: ${envirs.map(_._1.domain.domainId).mkString(", ")}-----------")
      val f: () => T = block _
      val result: T = envirs.size match {

        case 1 =>
          val a = envirs.head
          oneParty(a,f)

        case 2 =>
          val a = envirs.head
          val b = envirs.last
          twoParty(a,b,f)

        case x =>
          throw new RuntimeException(s"tests of $x Test Environments are not yet supported")

      }

      result
    }

    def oneParty[T](a: TestEnvirToOptDid, f: () => T): T = {
      val (ate, adido) = a
      val saveIt = ate.it
      ate.it = OneParty
      try {
        f()
      } finally {
        ate.it = saveIt
      }
    }

    def twoParty[T](a: TestEnvirToOptDid, b: TestEnvirToOptDid, f: () => T): T = {
      startInteraction(a,b)
      try {
        f()
      } finally {
        a._1.stopInteraction()
        b._1.stopInteraction()
      }
    }

  }

  /**
    * Simple pairwise DID creator designed to be readable for testing and troubleshooting.
    */
  def didFor(domainA: Domain, domainB: Domain) = s"${domainA.domainId}-did-for-${domainB.domainId}"

  def connect(a: TestEnvir, b: TestEnvir): Unit = connectDomains(a -> None, b-> None)

  def connectDomains(a: TestEnvirToOptDid, b: TestEnvirToOptDid): Unit = {
    val (ate, adido) = a
    val ad = ate.domain
    val (bte, bdido) = b
    val bd = bte.domain

    // use supplied DIDs, or generate some if they are not provided.
    val adid = adido getOrElse didFor(ad, bd)
    val bdid = bdido getOrElse didFor(bd, ad)

    ad addRelationship {
      PairwiseRelationship(bd.domainId, myDid = adid, theirDid = bdid)
    }

    bd addRelationship {
      PairwiseRelationship(ad.domainId, myDid = bdid, theirDid = adid)
    }
  }

  def startInteraction(a: TestEnvir, b: TestEnvir): Unit = startInteraction(a -> None, b -> None)

  def startInteraction(a: TestEnvirToOptDid, b: TestEnvirToOptDid): Unit = {
    val (ate, adido) = a
    val ad = ate.domain
    val (bte, bdido) = b
    val bd = bte.domain

    // use supplied DIDs, or generate some if they are not provided.
    val adid = adido getOrElse didFor(ad, bd)
    val bdid = bdido getOrElse didFor(bd, ad)

    if (ate.currentInteraction.nonEmpty) throw new RuntimeException(s"${ad.domainId} already in an interaction; exit the current interaction block before starting another")
    if (bte.currentInteraction.nonEmpty) throw new RuntimeException(s"${bd.domainId} already in an interaction; exit the current interaction block before starting another")

    ate.currentInteraction = Some(Interaction(adid, bdid))
    bte.currentInteraction = Some(Interaction(bdid, adid))

    val rel = ad.lookup(adid) getOrElse {
      connectDomains(a,b)
      ad.lookup_!(adid)
    }

  }

  case class Interaction(myDID: DID, theirDID: DID, threadId: Option[ThreadId]=None)

  /**
    * simple test environment for one side of a protocol interaction
    */
  class TestEnvir(val system: TestSystem, val domain: Domain, var it: InteractionType) extends Matchers {

    te =>

    private var _did: Option[DID] = None

    def did: Option[DID] = _did orElse {
      it match {
        case OneParty => Option(domain.domainId)
        case TwoParty => None
      }
    } orElse {
      currentInteraction map { _.myDID }
    }

    def did_! : DID = did getOrElse { throw new RuntimeException("DID not set") }

    def setDID(did: DID): Unit = {
      _did = Option(did)
    }

    var currentInteraction: Option[Interaction] = None

    /**
      * clears signals in current driver
      * Example:
      * {{{
      *   alice clear signals
      * }}}
      */
    def clear(x: DSL.SignalsWord): Unit = {
      controller.clear()
    }

    def stopInteraction(): Unit = {
      currentInteraction = None
    }

    case class InteractionDSL(them: TestEnvir) {

      def apply[T](block: => T): T = {
        startInteraction(te, them)
        try {
          block
        } finally {
          stopInteraction()
          them.stopInteraction()
        }
      }

    }

    val and: (TestEnvir) => InteractionDSL = that => InteractionDSL(that)

    /**
      * Gets a signal message from the Driver if it was sent one and checks that it matches the supplied type.
      *
      * Example:
      * {{{
      *   alice expect signal [MySignalType]
      * }}}
      */
    def expect[T : ClassTag](st: DSL.SignalClass[T]): T = expectAs(st){_ => ()}


    /**
      * Checks the state of the protocol instance matches a supplied type.
      *
      * Example:
      * {{{
      *   alice expect state [MyStateType]
      * }}}
      */
    def expect[T : ClassTag](st: DSL.StateClass[T]): T = expectAs(st){_ => ()}

    def expectAs[T : ClassTag](st: DSL.SignalClass[T])(test: T => Unit): T = {
      val signal = controller.takeNext()
      withClue("expecting a signal message but no signal found;") {
        signal should not be empty
      }
      withClue("unexpected signal;") {
        signal.get shouldBe a[SignalEnvelope[_]]
        signal.get.signalMsg shouldBe a[T]
        val rtn = signal.get.signalMsg.asInstanceOf[T]
        test(rtn)
        rtn
      }
    }

    def expectAs[T : ClassTag](st: DSL.StateClass[T])(test: T => Unit): T = {
      withClue("unexpected state;") {
        state shouldBe a[T]
        val rtn = state.asInstanceOf[T]
        test(rtn)
        rtn
      }
    }

    def container: Option[InMemoryProtocolContainer[P,R,M,E,S,I]] = {
      did flatMap system.containerForParti map { _.asInstanceOf[InMemoryProtocolContainer[P,R,M,E,S,I]] }
    }

    //assumes for now only one container per TestEnvir
    def container_! : InMemoryProtocolContainer[P,R,M,E,S,I] = {
      val pre = "Container not found; "
      val post = "two-party protocols should start interactions with `engage`; " +
        "one-party protocols should override defaultInteractionType (set to OneParty) " +
        "or set InteractionType in the `setup` method"

      Try { container } match {
        case Success(Some(c)) => c
        case Success(None) => throw new ContainerNotFoundException(pre + post)
        case Failure(e) => throw new ContainerNotFoundException(pre + s"cause: ${e.getMessage}; " + post, e)
      }
    }

    def controller: InteractionController = container_!.driver.get.asInstanceOf[InteractionController]

    def connect(that: TestEnvir): Unit = tk.connect(this, that)

    /**
      * Allows to provide specific pairwise DIDs.
      */
    def connect(them: TestEnvir, myDid: DID, theirDid: DID): Unit = {
      connectDomains (
        (this -> Some(myDid)),
        (them -> Some(theirDid))
      )
    }

    val engage: TestEnvir => EngagementDSL = { that =>
      it match {
        case TwoParty =>
        case _ => throw new RuntimeException("engage is only allowed for two party interaction types")
      }
      new EngagementDSL(this, that)
    }

    class EngagementDSL(me: TestEnvir, them: TestEnvir) {
      val ~ : Control => Unit = { ctl =>

        val myDID = didFor(me.domain, them.domain)
        val theirDID = didFor(them.domain, me.domain)

        me.setDID(myDID)
        them.setDID(theirDID)

        val rel = domain.lookup(myDID) getOrElse {
          connect(them)
          domain.lookup_!(myDID)
        }

        val threadId = domain.startInteractionRel(rel, ctl)

        me.currentInteraction = Some(Interaction(myDID, theirDID, Some(threadId)))
        them.currentInteraction = Some(Interaction(theirDID, myDID, Some(threadId)))
      }
    }

    val ~ : Control => Unit = control

    def control(ctl: Control): Unit = {
      val id_testing = MsgIdProvider.getNewMsgId

//      println("before container.isEmpty", id_testing)
      if (container.isEmpty) {
//        println("before it == OneParty", id_testing)
        if (it == OneParty) {
          domain.startSoloInteraction(ctl)
        } else {
          if (currentInteraction.isDefined) {
//            println("after currentInteraction.isDefined", id_testing)
            val env = if (currentInteraction.get.threadId.isEmpty) {
              val e = MsgUtil.encloseCtl(ctl)
              val tid = e.threadId
              currentInteraction = Some(currentInteraction.get.copy(threadId = Some(tid)))
              e
            } else {
              val tid = currentInteraction.get.threadId.get
              MsgUtil.encloseCtl(ctl, tid)
            }
            domain.handleControl(env, currentInteraction.get.myDID)
          }
        }
      } else {
//        println("before controller.control(ctl)", id_testing)
        controller.control(ctl)
      }
    }

    // little helpers to help keep test scripts clean and concise
    def backState = container_!.backState
    def stateVersion = container_!.backState.stateVersion
    def roster = container_!.backState.roster
    def role = container_!.backState.roster.selfRole_!
    def state = container_!.state
    def participantId = container_!.participantId
    def eventRecorder = container_!.eventRecorder
    def protocol = container_!.protocol


    def resetContainer(envir: TestEnvir, replayEvents: Boolean=false): Container = {
      val recorder = if (replayEvents) Option(envir.eventRecorder) else None

      val c = container_!

      val pce = ProtocolContainerElements(c.system, c.participantId, c.pinstId, c.threadId, c.definition, c.initProvider,
        recorder, c.driver, c.journalContext.dropLastOne)

      val newOne = domain.containerProvider(pce).asInstanceOf[Container]

      newOne.recoverOrInit()
      system.processAll()
      newOne
    }

    def walletAccess(w: WalletAccess): Unit = {
      domain.walletAccess(w)
    }

    def ledgerAccess(l: LedgerAccess): Unit = {
      domain.ledgerAccess(l)
    }

    def urlShortening(url: UrlShorteningAccess): Unit = {
      domain.urlShorteningAccess(url)
    }

    def initParams(m: Map[String, String]): Unit = {
      container.foreach( _ =>
        throw new Exception("Init parameters only have an effect if presented before the container is created.")
      )
      domain.initParams(m)
    }


  }

}


