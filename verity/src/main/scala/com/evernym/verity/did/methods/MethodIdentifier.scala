package com.evernym.verity.did.methods

// base trait for method identifier

trait Identifier {
  def didStr: String                          //did:indy:sovrin:123, did:indy:sovrin:builder:123  etc
  override def toString: String = didStr
}

trait MethodIdentifier extends Identifier {
  def method: String                              //key, sov, indy etc
  def methodIdentifier: String                    //123, sovrin:123, sovrin:builder:123 etc

  override def toString: String = methodIdentifier
}

trait NamespaceIdentifier extends MethodIdentifier {
  def namespace: String                           //sovrin, sovrin:builder
  def namespaceIdentifier: String                 //123
}