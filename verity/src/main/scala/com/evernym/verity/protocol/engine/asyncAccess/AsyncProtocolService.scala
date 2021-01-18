package com.evernym.verity.protocol.engine.asyncAccess

import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateMsg

//FIXME -> RTM: Don't settle on name
trait AsyncProtocolService {

  var awaitingUrlShortener: Boolean = false
  var awaitingLedger: Boolean = false
  var awaitingWallet: Boolean = false

  def urlShortenerComplete(): Unit = awaitingUrlShortener = false
  def urlShortenerInProgress(): Unit = awaitingUrlShortener = true

  def walletComplete(): Unit = awaitingUrlShortener = false
  def walletInProgress(): Unit = awaitingUrlShortener = true

  def ledgerComplete(): Unit = awaitingUrlShortener = false
  def ledgerInProgress(): Unit = awaitingUrlShortener = true

  def servicesComplete(pendingSegments: Option[SegmentedStateMsg]=None): Boolean =
    !(awaitingUrlShortener && awaitingLedger && awaitingWallet && pendingSegments.isDefined)

  def clearAsyncServices(): Unit = {
    urlShortenerComplete()
    walletComplete()
    ledgerComplete()
  }
}
