package com.evernym.verity.actor.agent.state

import com.evernym.verity.actor.agent.relationship.{DidDoc, EndpointADTUntyped, EndpointId, HasRelationship, KeyId, Relationship, Tags}
import com.evernym.verity.did.VerKeyStr

import scala.language.implicitConversions

trait HasRelationshipState extends HasRelationship {

  /**
   * initial relationship object
   * @return
   */
  def initialRel: Relationship

  private var _relationship: Relationship = initialRel
  def relationship: Relationship = _relationship

  /**
   * updates my did doc with given did doc
   * @param didDoc new/updated did doc
   * @return updated relationship
   */
  def updatedWithNewMyDidDoc(didDoc: DidDoc): Relationship =
    relationship.copy(myDidDoc = Option(didDoc))

  /**
   * key Id of the authorized key which is "controlled" by "this" agent.
   * as there can/will be more than 1 authorized keys (mostly in SelfRelationship)
   * there will a need to identify the one which is controlled by "this" agent
   */
  private var _thisAgentKeyId: Option[KeyId] = None
  def setThisAgentKeyId(keyId: KeyId): Unit = _thisAgentKeyId = Option(keyId)
  def thisAgentKeyId: Option[KeyId] = _thisAgentKeyId

  /**
   *
   * @param rel relationship object
   */
  def setRelationship(rel: Relationship): Unit = {
    _relationship = rel
  }

  def updateRelationship(rel: Relationship): Unit = {
    _relationship = rel
  }

  private def updateWithNewMyDidDoc(updatedDidDoc: DidDoc): Unit = {
    val updatedRel = updatedWithNewMyDidDoc(updatedDidDoc)
    updateRelationship(updatedRel)
  }

  def addNewAuthKeyToMyDidDoc(keyId: KeyId, verKey: VerKeyStr, tags: Set[Tags]): Unit = {
    updateWithNewMyDidDoc(relationship.myDidDoc_!.updatedWithNewAuthKey(keyId, verKey, tags))
  }

  def mergeAuthKeyToMyDidDoc(keyId: KeyId, verKey: VerKeyStr, tags: Set[Tags]): Unit = {
    updateWithNewMyDidDoc(relationship.myDidDoc_!.updatedWithMergedAuthKey(keyId, verKey, tags))
  }

  def addNewAuthKeyToMyDidDoc(keyId: KeyId, tags: Set[Tags]): Unit = {
    updateWithNewMyDidDoc(relationship.myDidDoc_!.updatedWithNewAuthKey(keyId, tags))
  }

  def addOrUpdateEndpointToMyDidDoc(endpoint: EndpointADTUntyped): Unit = {
    updateWithNewMyDidDoc(relationship.myDidDoc_!.updatedWithEndpoint(endpoint))
  }

  def removeEndpointById(id: EndpointId): Unit = {
    updateWithNewMyDidDoc(relationship.myDidDoc_!.updatedWithRemovedEndpointById(id))
  }

}
