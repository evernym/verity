package com.evernym.verity.actor.agent.relationship

import com.evernym.verity.actor.agent.relationship.AuthorizedKeys.KeyId
import com.evernym.verity.actor.agent.relationship.Relationship._
import com.evernym.verity.actor.agent.relationship.RelationshipException._
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.?=>



/**
 * There are five relationship variants, all of which extend the Relationship trait:
 * No Relationship
 * Self Relationship
 * Pairwise Relationship
 * Nwise Relationship
 * Anywise Relationship
 */

// ---------- NO ----------
// This is really the 'null' relationship. We could have used an Option of a
// Relationship, but that would require every use of a relationship to
// accommodate a None possibility. At least this way, we have an object that
// can be used generically with an expectation of a consistent relationship
// API.

case object NoRelationship extends Relationship {
  def name = "no relationship"
  override def myDidDoc: Option[DidDoc] = None
  override def theirDidDoc: Option[DidDoc] = None
  override def thoseDidDocs: Seq[DidDoc] = Seq.empty
  override def theirDid: Option[DID] = None

  override def updatedWithNewMyDidDoc[T <: Relationship](didDoc: DidDoc): T =
    throw new RuntimeException("not supported")
}


// ---------- SELF ----------

object SelfRelationship {
  def apply(myDidDoc: DidDoc): SelfRelationship = SelfRelationship(Option(myDidDoc))
  def empty: SelfRelationship = SelfRelationship(None)
}
case class SelfRelationship(myDidDoc: Option[DidDoc]=None) extends Relationship {
  override def name = "self"
  override def thoseDidDocs: Seq[DidDoc] = Seq.empty
  override def theirDidDoc: Option[DidDoc] = None

  override def updatedWithNewMyDidDoc[T <: Relationship](didDoc: DidDoc): Relationship =
    this.copy(myDidDoc = Option(didDoc))
}


// ---------- PAIRWISE ----------

object PairwiseRelationship {
  def apply(name: RelationshipName, myDid: DID, theirDid: DID): PairwiseRelationship = {
    PairwiseRelationship(name, Option(DidDoc(myDid)), Option(DidDoc(theirDid)))
  }

  def apply(name: RelationshipName, myDidDoc: DidDoc, theirDidDoc: DidDoc): PairwiseRelationship = {
    PairwiseRelationship(name, Option(myDidDoc), Option(theirDidDoc))
  }

  def empty: PairwiseRelationship = PairwiseRelationship("<empty>", None, None)
}
case class PairwiseRelationship(name: RelationshipName,
                                myDidDoc: Option[DidDoc] = None,
                                theirDidDoc: Option[DidDoc] = None) extends Relationship {
  override def thoseDidDocs: Seq[DidDoc] = theirDidDoc.toSeq

  override def updatedWithNewMyDidDoc[T](didDoc: DidDoc): PairwiseRelationship =
    this.copy(myDidDoc = Option(didDoc))
}


// ---------- NWISE ----------

/**
 * This relationship is not being used currently in the code base
 * but wanted to make sure the "relationship" code considers that type of scenario
 * and handles situations accordingly
 *
 * @param name
 * @param myDidDoc
 * @param thoseDidDocs
 */
case class NwiseRelationship(name: String, myDidDoc: Option[DidDoc] = None, thoseDidDocs: Seq[DidDoc] = Seq.empty) extends Relationship {

  def theirDidDoc: Option[DidDoc] = thoseDidDocs.size match {
    case 0|1 => thoseDidDocs.headOption
    case _ => throw new MoreThanOneExistsException
  }

  override def updatedWithNewMyDidDoc[T](didDoc: DidDoc): NwiseRelationship =
    this.copy(myDidDoc = Option(didDoc))
}


// ---------- ANYWISE ----------
// 'those' and 'their' did docs are empty because the assumption is that a
// public DID is primarily used to bootstrap pairwise or nwise relationships.

object AnywiseRelationship {
  def apply(myDidDoc: DidDoc): AnywiseRelationship = AnywiseRelationship(Option(myDidDoc))
  def empty: AnywiseRelationship = AnywiseRelationship(None)
}
case class AnywiseRelationship(myDidDoc: Option[DidDoc]=None) extends Relationship {
  override def name: RelationshipName = "anywise"

  override def thoseDidDocs: Seq[DidDoc] = Seq.empty
  override def theirDidDoc: Option[DidDoc] = None

  override def updatedWithNewMyDidDoc[T](didDoc: DidDoc): AnywiseRelationship =
    this.copy(myDidDoc = Option(didDoc))
}


// ---------- general ----------

/**
  * General trait for relationships with helper methods
  */
sealed trait Relationship {
  def name: RelationshipName

  def myDidDoc: Option[DidDoc]
  def theirDidDoc: Option[DidDoc]
  def thoseDidDocs: Seq[DidDoc]

  def myDid: Option[DID] = myDidDoc.map(_.did)
  def myDid_! : DID = myDidDoc_!.did
  def myDidDoc_! : DidDoc = myDidDoc getOrElse { throw new MyDidNotSetException }

  def theirDid: Option[DID] = theirDidDoc.map(_.did)
  def theirDid_! : DID = theirDidDoc_!.did
  def theirDidDoc_! : DidDoc = theirDidDoc getOrElse {
    throw new TheirDidNotSetException
  }

  def isEmpty: Boolean = myDidDoc.isEmpty && thoseDidDocs.isEmpty
  def nonEmpty: Boolean = !isEmpty
  def checkNotEmpty(): Unit = if (isEmpty) throw new RelationshipNotEmptyException

  private def authKeysByTags(didDoc: Option[DidDoc], tag: Tag): Vector[AuthorizedKeyLike] =
    didDoc.map(_.authorizedKeys.keys.filter(_.tags.contains(tag))).getOrElse(Vector.empty)

  def authKeyByTag(didDoc: Option[DidDoc], tag: Tag): Option[AuthorizedKeyLike] = {
    val authKeys = authKeysByTags(didDoc, tag)
    if (authKeys.isEmpty) None
    else if (authKeys.size == 1) authKeys.headOption
    else throw new RuntimeException("more than 1 auth key found with tag: " + tag)
  }

  def myDidDocAuthKeysByTag(tag: Tag): Vector[AuthorizedKeyLike] = {
    authKeysByTags(myDidDoc, tag)
  }

  def myDidDocAuthKeyByTag(tag: Tag): Option[AuthorizedKeyLike] = {
    authKeyByTag(myDidDoc, tag)
  }

  def theirDidDocAuthKeyByTag(tag: Tag): Option[AuthorizedKeyLike] = {
    authKeyByTag(theirDidDoc, tag)
  }

  def myDidDocAuthKeyById(keyId: KeyId): Option[AuthorizedKeyLike] = {
    myDidDoc.flatMap(_.authorizedKeys.keys.find(_.keyId == keyId))
  }

  def initParams(initParamNames: Set[ParameterName]): Parameters = Parameters {
    initParamNames map {
      case SELF_ID => Parameter(SELF_ID, myDid_!)
      case OTHER_ID => Parameter(OTHER_ID, theirDid_!)
      case pn => throw new RuntimeException(s"invalid parameter name for Relationship: $pn")
    }
  }

  def updatedWithNewMyDidDoc[T <: Relationship](didDoc: DidDoc): Relationship
}

object Relationship {
  type KeyId = String
  type URL = String
  type RelationshipName = String
}

// ---------- exceptions ----------

sealed abstract class RelationshipException(descr: String) extends RuntimeException(descr)
object RelationshipException {
  class MyDidNotSetException extends RelationshipException("DID for this relationship not yet established")
  class TheirDidNotSetException extends RelationshipException("DID for other party in this relationship not yet established")
  class MoreThanOneExistsException extends RelationshipException("more than one exists")
  class RelationshipNotEmptyException extends RelationshipException("relationship not empty")
}


trait HasRelationship {

  type RelationshipType <: Relationship

  def relationship: RelationshipType

  type RelationshipExceptionMap = RelationshipException ?=> Exception

  /**
    * Override this method to add custom mapping of Exceptions.
    * This is useful when the RelationshipException thrown might be too low
    * level, and upstream code is expecting a more context-specific exception.
    * For example, myDidDoc_! might throw MyDidNotSetException, and the
    * code upstream from the caller might be expecting something like
    * AgentNotYetProvisionedException instead. Rather than making the class
    * implementing HasRelationship create wrappers for calls to Relationship
    * methods with custom exception handling, one could simply override this
    * method with a custom exception mapping.
    *
    * Example:
    * override def relationshipExceptionMap = {
    *   case MyDidNotSetException => AgentNotYetProvisionedException
    *   case TheirDidNotSetException => ConnectionNotCompleteException
    * }
    *
    */
  def relationshipExceptionMap: RelationshipExceptionMap = PartialFunction.empty

  // identity partial function for exceptions
  private def excIdent: RelationshipException ?=> RelationshipException = { case x => x }

  // Exception handler
  private lazy val excHandler: Throwable ?=> Nothing = {
    relationshipExceptionMap orElse excIdent andThen { e => throw e}
    }.asInstanceOf[PartialFunction[Throwable,Nothing]]

  private def exmap[A](f: => A): A = try f catch excHandler

  def myDidDoc: Option[DidDoc] = exmap { relationship.myDidDoc }
  def myDid: Option[DID]       = exmap { relationship.myDid }
  def myDidDoc_! : DidDoc      = exmap { relationship.myDidDoc_! }
  def myDid_! : DID            = exmap { relationship.myDid_! }

  def theirDidDoc: Option[DidDoc] = exmap { relationship.theirDidDoc }
  def theirDid: Option[DID]       = exmap { relationship.theirDid }
  def theirDidDoc_! : DidDoc      = exmap { relationship.theirDidDoc_! }
  def theirDid_! : DID            = exmap { relationship.theirDid_! }

  def thoseDidDocs: Seq[DidDoc]  = exmap { relationship.thoseDidDocs }

  def checkRelationshipSet(): Unit = exmap { relationship.checkNotEmpty() }

}
