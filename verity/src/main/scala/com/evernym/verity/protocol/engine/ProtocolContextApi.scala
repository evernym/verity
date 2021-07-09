package com.evernym.verity.protocol.engine

import java.util.concurrent.TimeUnit
import com.evernym.verity.ServiceEndpoint
import com.evernym.verity.metrics.CustomMetrics._
import com.evernym.verity.metrics.{MetricsUnit, MetricsWriter}
import com.evernym.verity.protocol.engine.asyncapi.ledger.LedgerAccess
import com.evernym.verity.protocol.engine.asyncapi.urlShorter.UrlShorteningAccess
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateContextApi
import com.evernym.verity.protocol.legacy.services.ProtocolServices
import com.github.ghik.silencer.silent

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Future}

/**
  * A representation of the Container to the protocol it holds that embodies
  * the whole of the protocols access to the outside world. It is highly
  * constrained by design.
  *
  */
trait ProtocolContextApi[P,R,M,E,S,I]
  extends SegmentedStateContextApi
   with HasLogger { this: ProtocolContext[P,R,M,E,S,I] =>

  def apply[A >: E with ProtoSystemEvent](event: A): Unit

  def signal(signalMsg: Any): Unit

  def threadId: Option[ThreadId] = getInFlight.threadId
  def `threadId_!`: ThreadId = getInFlight.threadId.getOrElse(throw new RuntimeException("threadId is unknown"))

  def getState: S

  def getBackState: BackState

  def getRoster: Roster[R]

  def getInFlight: InFlight

  def send[T](msg: M, toRole: Option[R]=None, fromRole: Option[R]=None): Unit

  def serviceEndpoint: ServiceEndpoint
  
  //TODO how a message is sent should probably be abstracted; would like to fold this into the general send method above
  //TODO we shouldn't be exposing general sms capabilities to protocol authors
  def sendSMS(toPhoneNumber: String, msg: String): Future[String]

  def updatedRoster(params: Seq[InitParamBase]): Roster[R]

  def wallet: WalletAccess

  def ledger: LedgerAccess

  def urlShortening: UrlShorteningAccess

  // TODO as soon as all references to these are resolved, remove them
  /**
    * Use of this member violates protocol encapsulation. Use getState instead.
    * @return
    */

  /**
    * At the time of creating this, there shouldn't be a reason a protocol
    * implementation needs access to it's name and version.
    */
  @deprecated("Use of this member is suspicious. Why do we need to know the protocol version inside of a protocol?.", PROTOCOL_ENCAPSULATION_FIX_DATE)
  def version_WARNING_SUSPICIOUS_USAGE: String = definition.msgFamily.protoRef.msgFamilyVersion


  //TODO: no new protocols are supposed to use services
  //it is only being used by few existing protocols and sooner we should get rid of usages of the services
  // noinspection ScalaDeprecation
  @silent
  def SERVICES_DEPRECATED: ProtocolServices[M,E,I] = _services.getOrElse(throw new RuntimeException("services are not available"))

  @silent
  def DEPRECATED_convertAsyncToSync[T](fut: Future[T]): T = {
    metricsWriter.get().histogramUpdate(
      AS_BLOCKING_WALLET_API_CALL_COUNT,
      MetricsUnit.None,
      1,
      Map("protocol" -> definition.msgFamily.protoRef.toString),
    )
    Await.result(fut, FiniteDuration(60, TimeUnit.SECONDS))
  }
}
