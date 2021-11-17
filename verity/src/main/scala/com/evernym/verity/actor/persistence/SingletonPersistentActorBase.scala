package com.evernym.verity.actor.persistence

trait SingletonPersistentActorBase extends BasePersistentTimeoutActor {

  def receiveBaseEvent: Receive
  def receiveBaseCmd: Receive

  def receiveSpecificEvent: Receive
  def receiveSpecificCmd: Receive

  override final def receiveEvent: Receive = receiveBaseEvent orElse receiveSpecificEvent
  override final def receiveCmd: Receive = receiveBaseCmd orElse receiveSpecificCmd

}
