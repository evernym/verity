package com.evernym.verity.protocol.engine.asyncProtocol

import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateMsg

//FIXME -> RTM: Protocol Registry example. Instead of unique function, register
trait AsyncProtocolProgress {

  var awaitingUrlShortener: Boolean = false
  var awaitingLedger: Boolean = false
  var awaitingWallet: Boolean = false

  def urlShortenerComplete(): Unit = awaitingUrlShortener = false
  def urlShortenerInProgress(): Unit = awaitingUrlShortener = true

  def walletComplete(): Unit = awaitingUrlShortener = false
  def walletInProgress(): Unit = awaitingUrlShortener = true

  def ledgerComplete(): Unit = awaitingUrlShortener = false
  def ledgerInProgress(): Unit = awaitingUrlShortener = true

  /**
   * Things like the url shortener and the wallet and ledger services are internal to a protocol and need to be complete
   *  before segmented state storage and event persistent which are post protocol.
   */
  def internalAsyncProtocolServicesComplete(): Boolean = !(awaitingUrlShortener || awaitingLedger || awaitingWallet)

  /**
   * This includes the internal protocol services and state storage. It doesn't include event persistence (Actor Related).
   */
  def allAsyncProtocolServicesComplete(pendingSegments: Option[SegmentedStateMsg]=None): Boolean =
    pendingSegments.isEmpty && internalAsyncProtocolServicesComplete()

  def clearInternalAsyncServices(): Unit = {
    urlShortenerComplete()
    walletComplete()
    ledgerComplete()
  }
}
