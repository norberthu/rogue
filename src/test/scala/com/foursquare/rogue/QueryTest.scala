// Copyright 2011 Foursquare Labs Inc. All Rights Reserved.
package com.foursquare.rogue

import com.foursquare.rogue.Rogue._

import java.util.regex.Pattern
import net.liftweb.mongodb.record._
import net.liftweb.mongodb.record.field._
import net.liftweb.record.field._
import net.liftweb.record._
import org.bson.types._
import org.joda.time.{DateTime, DateTimeZone}

import org.junit._
import org.specs.SpecsMatchers

/////////////////////////////////////////////////
// Sample records for testing
/////////////////////////////////////////////////
object VenueStatus extends Enumeration {
  val open = Value("Open")
  val closed = Value("Closed")
}

class Venue extends MongoRecord[Venue] with MongoId[Venue] {
  def meta = Venue
  object legacyid extends LongField(this) { override def name = "legid" }
  object userid extends LongField(this)
  object venuename extends StringField(this, 255)
  object mayor extends LongField(this)
  object mayor_count extends LongField(this)
  object closed extends BooleanField(this)
  object tags extends MongoListField[Venue, String](this)
  object popularity extends MongoListField[Venue, Long](this)
  object categories extends MongoListField[Venue, ObjectId](this)
  object geolatlng extends MongoCaseClassField[Venue, LatLong](this) { override def name = "latlng" }
  object last_updated extends DateTimeField(this)
  object status extends EnumNameField(this, VenueStatus) { override def name = "status" }
  object claims extends BsonRecordListField(this, VenueClaimBson)
  object lastClaim extends BsonRecordField(this, VenueClaimBson)
}
object Venue extends Venue with MongoMetaRecord[Venue] {
  object CustomIndex extends IndexModifier("custom")
  val idIdx = Venue.index(_._id, Asc)
  val legIdx = Venue.index(_.legacyid, Desc)
  val geoIdx = Venue.index(_.geolatlng, TwoD)
  val geoCustomIdx = Venue.index(_.geolatlng, CustomIndex, _.tags, Asc)

  trait FK[T <: FK[T]] extends MongoRecord[T] {
    self: T=>
    object venueid extends ObjectIdField[T](this) with HasMongoForeignObjectId[Venue] {
      override def name = "vid"
    }
  }
}

object ClaimStatus extends Enumeration {
  val pending = Value("Pending approval")
  val approved = Value("Approved")
}

class VenueClaim extends MongoRecord[VenueClaim] with MongoId[VenueClaim] with Venue.FK[VenueClaim] {
  def meta = VenueClaim
  object userid extends LongField(this) { override def name = "uid" }
  object status extends EnumNameField(this, ClaimStatus)
}
object VenueClaim extends VenueClaim with MongoMetaRecord[VenueClaim] {
  override def fieldOrder = List(status, _id, userid, venueid)
}

class VenueClaimBson extends BsonRecord[VenueClaimBson] {
  def meta = VenueClaimBson
  object userid extends LongField(this) { override def name = "uid" }
  object status extends EnumNameField(this, ClaimStatus)
}
object VenueClaimBson extends VenueClaimBson with BsonMetaRecord[VenueClaimBson] {
  override def fieldOrder = List(status, userid)
}


case class OneComment(timestamp: String, userid: Long, comment: String)
class Comment extends MongoRecord[Comment] with MongoId[Comment] {
  def meta = Comment
  object comments extends MongoCaseClassListField[Comment, OneComment](this)
}
object Comment extends Comment with MongoMetaRecord[Comment] {
  val idx1 = Comment.index(_._id, Asc)
}

class Tip extends MongoRecord[Tip] with MongoId[Tip] {
  def meta = Tip
  object legacyid extends LongField(this) { override def name = "legid" }
  object counts extends MongoMapField[Tip, Long](this)
}
object Tip extends Tip with MongoMetaRecord[Tip]

object ConsumerPrivilege extends Enumeration {
  val awardBadges = Value("Award badges")
}

class OAuthConsumer extends MongoRecord[OAuthConsumer] with MongoId[OAuthConsumer] {
  def meta = OAuthConsumer
  object privileges extends MongoListField[OAuthConsumer, ConsumerPrivilege.Value](this)
}
object OAuthConsumer extends OAuthConsumer with MongoMetaRecord[OAuthConsumer]

case class V1(legacyid: Long)
case class V2(legacyid: Long, userid: Long)
case class V3(legacyid: Long, userid: Long, mayor: Long)
case class V4(legacyid: Long, userid: Long, mayor: Long, mayor_count: Long)
case class V5(legacyid: Long, userid: Long, mayor: Long, mayor_count: Long, closed: Boolean)
case class V6(legacyid: Long, userid: Long, mayor: Long, mayor_count: Long, closed: Boolean, tags: List[String])

/////////////////////////////////////////////////
// Actual tests
/////////////////////////////////////////////////

class QueryTest extends SpecsMatchers {

  @Test
  def testProduceACorrectJSONQueryString {
    val d1 = new DateTime(2010, 5, 1, 0, 0, 0, 0, DateTimeZone.UTC)
    val d2 = new DateTime(2010, 5, 2, 0, 0, 0, 0, DateTimeZone.UTC)
    val oid1 = new ObjectId(d1.toDate, 0, 0)
    val oid2 = new ObjectId(d2.toDate, 0, 0)
    val oid = new ObjectId
    val ven1 = Venue.createRecord._id(oid1)

    // eqs
    Venue where (_.mayor eqs 1)               toString() must_== """db.venues.find({ "mayor" : 1})"""
    Venue where (_.venuename eqs "Starbucks") toString() must_== """db.venues.find({ "venuename" : "Starbucks"})"""
    Venue where (_.closed eqs true)           toString() must_== """db.venues.find({ "closed" : true})"""
    Venue where (_._id eqs oid)               toString() must_== ("""db.venues.find({ "_id" : { "$oid" : "%s"}})""" format oid.toString)
    VenueClaim where (_.status eqs ClaimStatus.approved) toString() must_== """db.venueclaims.find({ "status" : "Approved"})"""

    VenueClaim where (_.venueid eqs oid)      toString() must_== ("""db.venueclaims.find({ "vid" : { "$oid" : "%s"}})""" format oid.toString)
    VenueClaim where (_.venueid eqs ven1.id)  toString() must_== ("""db.venueclaims.find({ "vid" : { "$oid" : "%s"}})""" format oid1.toString)
    VenueClaim where (_.venueid eqs ven1)     toString() must_== ("""db.venueclaims.find({ "vid" : { "$oid" : "%s"}})""" format oid1.toString)

    // neq,lt,gt
    Venue where (_.mayor_count neqs 5) toString() must_== """db.venues.find({ "mayor_count" : { "$ne" : 5}})"""
    Venue where (_.mayor_count < 5)    toString() must_== """db.venues.find({ "mayor_count" : { "$lt" : 5}})"""
    Venue where (_.mayor_count lt 5)   toString() must_== """db.venues.find({ "mayor_count" : { "$lt" : 5}})"""
    Venue where (_.mayor_count <= 5)   toString() must_== """db.venues.find({ "mayor_count" : { "$lte" : 5}})"""
    Venue where (_.mayor_count lte 5)  toString() must_== """db.venues.find({ "mayor_count" : { "$lte" : 5}})"""
    Venue where (_.mayor_count > 5)    toString() must_== """db.venues.find({ "mayor_count" : { "$gt" : 5}})"""
    Venue where (_.mayor_count gt 5)   toString() must_== """db.venues.find({ "mayor_count" : { "$gt" : 5}})"""
    Venue where (_.mayor_count >= 5)   toString() must_== """db.venues.find({ "mayor_count" : { "$gte" : 5}})"""
    Venue where (_.mayor_count gte 5)  toString() must_== """db.venues.find({ "mayor_count" : { "$gte" : 5}})"""
    Venue where (_.mayor_count between (3, 5)) toString() must_== """db.venues.find({ "mayor_count" : { "$gte" : 3 , "$lte" : 5}})"""
    VenueClaim where (_.status neqs ClaimStatus.approved) toString() must_== """db.venueclaims.find({ "status" : { "$ne" : "Approved"}})"""

    // in,nin
    Venue where (_.legacyid in List(123L, 456L)) toString() must_== """db.venues.find({ "legid" : { "$in" : [ 123 , 456]}})"""
    Venue where (_.venuename nin List("Starbucks", "Whole Foods")) toString() must_== """db.venues.find({ "venuename" : { "$nin" : [ "Starbucks" , "Whole Foods"]}})"""
    VenueClaim where (_.status in List(ClaimStatus.approved, ClaimStatus.pending))  toString() must_== """db.venueclaims.find({ "status" : { "$in" : [ "Approved" , "Pending approval"]}})"""
    VenueClaim where (_.status nin List(ClaimStatus.approved, ClaimStatus.pending)) toString() must_== """db.venueclaims.find({ "status" : { "$nin" : [ "Approved" , "Pending approval"]}})"""

    VenueClaim where (_.venueid in List(ven1.id))  toString() must_== ("""db.venueclaims.find({ "vid" : { "$in" : [ { "$oid" : "%s"}]}})""" format oid1.toString)
    VenueClaim where (_.venueid in List(ven1))     toString() must_== ("""db.venueclaims.find({ "vid" : { "$in" : [ { "$oid" : "%s"}]}})""" format oid1.toString)

    VenueClaim where (_.venueid nin List(ven1.id))   toString() must_== ("""db.venueclaims.find({ "vid" : { "$nin" : [ { "$oid" : "%s"}]}})""" format oid1.toString)
    VenueClaim where (_.venueid nin List(ven1))      toString() must_== ("""db.venueclaims.find({ "vid" : { "$nin" : [ { "$oid" : "%s"}]}})""" format oid1.toString)


    // exists
    Venue where (_._id exists true) toString() must_== """db.venues.find({ "_id" : { "$exists" : true}})"""

    // startsWith, regex
    Venue where (_.venuename startsWith "Starbucks") toString() must_== """db.venues.find({ "venuename" : { "$regex" : "^\\QStarbucks\\E" , "$options" : ""}})"""
    Venue where (_.venuename regexWarningNotIndexed Pattern.compile("Star.*")) toString() must_== """db.venues.find({ "venuename" : { "$regex" : "Star.*" , "$options" : ""}})"""
    Venue where (_.venuename matches Pattern.compile("Star.*")) toString() must_== """db.venues.find({ "venuename" : { "$regex" : "Star.*" , "$options" : ""}})"""

    // all, in, size, contains, at
    Venue where (_.tags all List("db", "ka"))   toString() must_== """db.venues.find({ "tags" : { "$all" : [ "db" , "ka"]}})"""
    Venue where (_.tags in  List("db", "ka"))   toString() must_== """db.venues.find({ "tags" : { "$in" : [ "db" , "ka"]}})"""
    Venue where (_.tags nin List("db", "ka"))   toString() must_== """db.venues.find({ "tags" : { "$nin" : [ "db" , "ka"]}})"""
    Venue where (_.tags size 3)                 toString() must_== """db.venues.find({ "tags" : { "$size" : 3}})"""
    Venue where (_.tags contains "karaoke")     toString() must_== """db.venues.find({ "tags" : "karaoke"})"""
    Venue where (_.tags notcontains "karaoke")  toString() must_== """db.venues.find({ "tags" : { "$ne" : "karaoke"}})"""
    Venue where (_.popularity contains 3)       toString() must_== """db.venues.find({ "popularity" : 3})"""
    Venue where (_.popularity at 0 eqs 3)       toString() must_== """db.venues.find({ "popularity.0" : 3})"""
    Venue where (_.categories at 0 eqs oid)     toString() must_== """db.venues.find({ "categories.0" : { "$oid" : "%s"}})""".format(oid.toString)
    Venue where (_.tags at 0 startsWith "kara") toString() must_== """db.venues.find({ "tags.0" : { "$regex" : "^\\Qkara\\E" , "$options" : ""}})"""
    // alternative syntax
    Venue where (_.tags idx 0 startsWith "kara") toString() must_== """db.venues.find({ "tags.0" : { "$regex" : "^\\Qkara\\E" , "$options" : ""}})"""

    // maps
    Tip where (_.counts at "foo" eqs 3) toString() must_== """db.tips.find({ "counts.foo" : 3})"""

    // near
    Venue where (_.geolatlng near (39.0, -74.0, Degrees(0.2)))     toString() must_== """db.venues.find({ "latlng" : { "$near" : [ 39.0 , -74.0 , 0.2]}})"""
    Venue where (_.geolatlng withinCircle(1.0, 2.0, Degrees(0.3))) toString() must_== """db.venues.find({ "latlng" : { "$within" : { "$center" : [ [ 1.0 , 2.0] , 0.3]}}})"""
    Venue where (_.geolatlng withinBox(1.0, 2.0, 3.0, 4.0))        toString() must_== """db.venues.find({ "latlng" : { "$within" : { "$box" : [ [ 1.0 , 2.0] , [ 3.0 , 4.0]]}}})"""
    Venue where (_.geolatlng eqs (45.0, 50.0))                     toString() must_== """db.venues.find({ "latlng" : [ 45.0 , 50.0]})"""
    Venue where (_.geolatlng neqs (31.0, 23.0))                    toString() must_== """db.venues.find({ "latlng" : { "$ne" : [ 31.0 , 23.0]}})"""
    Venue where (_.geolatlng eqs LatLong(45.0, 50.0))              toString() must_== """db.venues.find({ "latlng" : [ 45.0 , 50.0]})"""
    Venue where (_.geolatlng neqs LatLong(31.0, 23.0))             toString() must_== """db.venues.find({ "latlng" : { "$ne" : [ 31.0 , 23.0]}})"""

    // ObjectId before, after, between
    Venue where (_._id before d2)        toString() must_== """db.venues.find({ "_id" : { "$lt" : { "$oid" : "%s"}}})""".format(oid2.toString)
    Venue where (_._id after d1)         toString() must_== """db.venues.find({ "_id" : { "$gt" : { "$oid" : "%s"}}})""".format(oid1.toString)
    Venue where (_._id between (d1, d2)) toString() must_== """db.venues.find({ "_id" : { "$gt" : { "$oid" : "%s"} , "$lt" : { "$oid" : "%s"}}})""".format(oid1.toString, oid2.toString)

    // DateTime before, after, between
    Venue where (_.last_updated before d2)        toString() must_== """db.venues.find({ "last_updated" : { "$lt" : { "$date" : "2010-05-02T00:00:00Z"}}})"""
    Venue where (_.last_updated after d1)         toString() must_== """db.venues.find({ "last_updated" : { "$gt" : { "$date" : "2010-05-01T00:00:00Z"}}})"""
    Venue where (_.last_updated between (d1, d2)) toString() must_== """db.venues.find({ "last_updated" : { "$gt" : { "$date" : "2010-05-01T00:00:00Z"} , "$lt" : { "$date" : "2010-05-02T00:00:00Z"}}})"""

    // Case class list field
    Comment where (_.comments.unsafeField[Int]("z") eqs 123) toString() must_== """db.comments.find({ "comments.z" : 123})"""
    Comment where (_.comments.unsafeField[String]("comment") eqs "hi") toString() must_== """db.comments.find({ "comments.comment" : "hi"})"""

    // BsonRecordField subfield queries
    Venue where (_.claims.subfield(_.status) eqs ClaimStatus.approved) toString() must_== """db.venues.find({ "claims.status" : "Approved"})"""
    Venue where (_.lastClaim.subfield(_.userid) eqs 123)               toString() must_== """db.venues.find({ "lastClaim.uid" : 123})"""

    // Enumeration list
    OAuthConsumer where (_.privileges contains ConsumerPrivilege.awardBadges) toString() must_== """db.oauthconsumers.find({ "privileges" : "Award badges"})"""
    OAuthConsumer where (_.privileges at 0 eqs ConsumerPrivilege.awardBadges) toString() must_== """db.oauthconsumers.find({ "privileges.0" : "Award badges"})"""

    // Field type
    Venue where (_.legacyid hastype MongoType.String) toString() must_== """db.venues.find({ "legid" : { "$type" : 2}})"""

    // Modulus
    Venue where (_.legacyid mod (5, 1)) toString() must_== """db.venues.find({ "legid" : { "$mod" : [ 5 , 1]}})"""

    // compound queries
    Venue where (_.mayor eqs 1) and (_.tags contains "karaoke") toString() must_== """db.venues.find({ "mayor" : 1 , "tags" : "karaoke"})"""
    Venue where (_.mayor eqs 1) and (_.mayor_count eqs 5)       toString() must_== """db.venues.find({ "mayor" : 1 , "mayor_count" : 5})"""
    Venue where (_.mayor eqs 1) and (_.mayor_count lt 5)        toString() must_== """db.venues.find({ "mayor" : 1 , "mayor_count" : { "$lt" : 5}})"""
    Venue where (_.mayor eqs 1) and (_.mayor_count gt 3) and (_.mayor_count lt 5) toString() must_== """db.venues.find({ "mayor" : 1 , "mayor_count" : { "$lt" : 5 , "$gt" : 3}})"""

    // queries with no clauses
    metaRecordToQueryBuilder(Venue) toString() must_== "db.venues.find({ })"
    Venue orderDesc(_._id) toString() must_== """db.venues.find({ }).sort({ "_id" : -1})"""

    // ordered queries
    Venue where (_.mayor eqs 1) orderAsc(_.legacyid) toString() must_== """db.venues.find({ "mayor" : 1}).sort({ "legid" : 1})"""
    Venue where (_.mayor eqs 1) orderDesc(_.legacyid) andAsc(_.userid) toString() must_== """db.venues.find({ "mayor" : 1}).sort({ "legid" : -1 , "userid" : 1})"""

    // select queries
    Venue where (_.mayor eqs 1) select(_.legacyid) toString() must_== """db.venues.find({ "mayor" : 1}, { "legid" : 1})"""
    Venue where (_.mayor eqs 1) select(_.legacyid, _.userid) toString() must_== """db.venues.find({ "mayor" : 1}, { "legid" : 1 , "userid" : 1})"""
    Venue where (_.mayor eqs 1) select(_.legacyid, _.userid, _.mayor) toString() must_== """db.venues.find({ "mayor" : 1}, { "legid" : 1 , "userid" : 1 , "mayor" : 1})"""
    Venue where (_.mayor eqs 1) select(_.legacyid, _.userid, _.mayor, _.mayor_count) toString() must_== """db.venues.find({ "mayor" : 1}, { "legid" : 1 , "userid" : 1 , "mayor" : 1 , "mayor_count" : 1})"""
    Venue where (_.mayor eqs 1) select(_.legacyid, _.userid, _.mayor, _.mayor_count, _.closed) toString() must_== """db.venues.find({ "mayor" : 1}, { "legid" : 1 , "userid" : 1 , "mayor" : 1 , "mayor_count" : 1 , "closed" : 1})"""
    Venue where (_.mayor eqs 1) select(_.legacyid, _.userid, _.mayor, _.mayor_count, _.closed, _.tags) toString() must_== """db.venues.find({ "mayor" : 1}, { "legid" : 1 , "userid" : 1 , "mayor" : 1 , "mayor_count" : 1 , "closed" : 1 , "tags" : 1})"""

    // select case queries
    Venue where (_.mayor eqs 1) selectCase(_.legacyid, V1) toString() must_== """db.venues.find({ "mayor" : 1}, { "legid" : 1})"""
    Venue where (_.mayor eqs 1) selectCase(_.legacyid, _.userid, V2) toString() must_== """db.venues.find({ "mayor" : 1}, { "legid" : 1 , "userid" : 1})"""
    Venue where (_.mayor eqs 1) selectCase(_.legacyid, _.userid, _.mayor, V3) toString() must_== """db.venues.find({ "mayor" : 1}, { "legid" : 1 , "userid" : 1 , "mayor" : 1})"""
    Venue where (_.mayor eqs 1) selectCase(_.legacyid, _.userid, _.mayor, _.mayor_count, V4) toString() must_== """db.venues.find({ "mayor" : 1}, { "legid" : 1 , "userid" : 1 , "mayor" : 1 , "mayor_count" : 1})"""
    Venue where (_.mayor eqs 1) selectCase(_.legacyid, _.userid, _.mayor, _.mayor_count, _.closed, V5) toString() must_== """db.venues.find({ "mayor" : 1}, { "legid" : 1 , "userid" : 1 , "mayor" : 1 , "mayor_count" : 1 , "closed" : 1})"""
    Venue where (_.mayor eqs 1) selectCase(_.legacyid, _.userid, _.mayor, _.mayor_count, _.closed, _.tags, V6) toString() must_== """db.venues.find({ "mayor" : 1}, { "legid" : 1 , "userid" : 1 , "mayor" : 1 , "mayor_count" : 1 , "closed" : 1 , "tags" : 1})"""

    // select subfields
    Tip where (_.legacyid eqs 1) select (_.counts at "foo") toString() must_== """db.tips.find({ "legid" : 1}, { "counts.foo" : 1})"""
    Venue where (_.legacyid eqs 1) select (_.geolatlng.unsafeField[Double]("lat")) toString() must_== """db.venues.find({ "legid" : 1}, { "latlng.lat" : 1})"""
    Venue where (_.legacyid eqs 1) select (_.lastClaim.subfield(_.status)) toString() must_== """db.venues.find({ "legid" : 1}, { "lastClaim.status" : 1})"""

    // TODO: case class list fields
    // Comment select(_.comments.unsafeField[Long]("userid")) toString() must_== """db.venues.find({ }, { "comments.userid" : 1})"""

    // empty queries
    Venue where (_.mayor in List())      toString() must_== "empty query"
    Venue where (_.tags in List())       toString() must_== "empty query"
    Venue where (_.tags all List())      toString() must_== "empty query"
    Comment where (_.comments in List()) toString() must_== "empty query"
    Venue where (_.tags contains "karaoke") and (_.mayor in List()) toString() must_== "empty query"
    Venue where (_.mayor in List()) and (_.tags contains "karaoke") toString() must_== "empty query"

    // out of order and doesn't screw up earlier params
    Venue limit(10) where (_.mayor eqs 1) toString() must_== """db.venues.find({ "mayor" : 1}).limit(10)"""
    Venue orderDesc(_._id) and (_.mayor eqs 1) toString() must_== """db.venues.find({ "mayor" : 1}).sort({ "_id" : -1})"""
    Venue orderDesc(_._id) skip(3) and (_.mayor eqs 1) toString() must_== """db.venues.find({ "mayor" : 1}).sort({ "_id" : -1}).skip(3)"""

    // Scan should be the same as and/where
    Venue where (_.mayor eqs 1) scan (_.tags contains "karaoke") toString() must_== """db.venues.find({ "mayor" : 1 , "tags" : "karaoke"})"""
    Venue scan (_.mayor eqs 1) and (_.mayor_count eqs 5)         toString() must_== """db.venues.find({ "mayor" : 1 , "mayor_count" : 5})"""
    Venue scan (_.mayor eqs 1) scan (_.mayor_count lt 5)         toString() must_== """db.venues.find({ "mayor" : 1 , "mayor_count" : { "$lt" : 5}})"""
    Venue where (_.mayor in List()) scan (_.mayor_count eqs 5)   toString() must_== "empty query"

    // limit, limitOpt, skip, skipOpt
    Venue where (_.mayor eqs 1) limit(10)          toString() must_== """db.venues.find({ "mayor" : 1}).limit(10)"""
    Venue where (_.mayor eqs 1) limitOpt(Some(10)) toString() must_== """db.venues.find({ "mayor" : 1}).limit(10)"""
    Venue where (_.mayor eqs 1) limitOpt(None)     toString() must_== """db.venues.find({ "mayor" : 1})"""
    Venue where (_.mayor eqs 1) skip(10)           toString() must_== """db.venues.find({ "mayor" : 1}).skip(10)"""
    Venue where (_.mayor eqs 1) skipOpt(Some(10))  toString() must_== """db.venues.find({ "mayor" : 1}).skip(10)"""
    Venue where (_.mayor eqs 1) skipOpt(None)      toString() must_== """db.venues.find({ "mayor" : 1})"""

    // raw query clauses
    Venue where (_.mayor eqs 1) raw (_.add("$where", "this.a > 3")) toString() must_== """db.venues.find({ "mayor" : 1 , "$where" : "this.a > 3"})"""
  }

  @Test
  def testModifyQueryShouldProduceACorrectJSONQueryString {
    val d1 = new DateTime(2010, 5, 1, 0, 0, 0, 0, DateTimeZone.UTC)

    val query = """db.venues.update({ "legid" : 1}, """
    val suffix = ", false, false)"
    Venue where (_.legacyid eqs 1) modify (_.venuename setTo "fshq") toString() must_== query + """{ "$set" : { "venuename" : "fshq"}}""" + suffix
    Venue where (_.legacyid eqs 1) modify (_.mayor_count setTo 3)    toString() must_== query + """{ "$set" : { "mayor_count" : 3}}""" + suffix
    Venue where (_.legacyid eqs 1) modify (_.mayor_count unset)      toString() must_== query + """{ "$unset" : { "mayor_count" : 1}}""" + suffix

    // Numeric
    Venue where (_.legacyid eqs 1) modify (_.mayor_count inc 3) toString() must_== query + """{ "$inc" : { "mayor_count" : 3}}""" + suffix

    // Enumeration
    val query2 = """db.venueclaims.update({ "uid" : 1}, """
    VenueClaim where (_.userid eqs 1) modify (_.status setTo ClaimStatus.approved) toString() must_== query2 + """{ "$set" : { "status" : "Approved"}}""" + suffix

    // Calendar
    Venue where (_.legacyid eqs 1) modify (_.last_updated setTo d1) toString() must_== query + """{ "$set" : { "last_updated" : { "$date" : "2010-05-01T00:00:00Z"}}}""" + suffix
    Venue where (_.legacyid eqs 1) modify (_.last_updated setTo d1.toGregorianCalendar) toString() must_== query + """{ "$set" : { "last_updated" : { "$date" : "2010-05-01T00:00:00Z"}}}""" + suffix

    // LatLong
    val ll = LatLong(37.4, -73.9)
    Venue where (_.legacyid eqs 1) modify (_.geolatlng setTo ll) toString() must_== query + """{ "$set" : { "latlng" : [ 37.4 , -73.9]}}""" + suffix

    // Lists
    Venue where (_.legacyid eqs 1) modify (_.popularity setTo List(5))       toString() must_== query + """{ "$set" : { "popularity" : [ 5]}}""" + suffix
    Venue where (_.legacyid eqs 1) modify (_.popularity push 5)              toString() must_== query + """{ "$push" : { "popularity" : 5}}""" + suffix
    Venue where (_.legacyid eqs 1) modify (_.tags pushAll List("a", "b"))    toString() must_== query + """{ "$pushAll" : { "tags" : [ "a" , "b"]}}""" + suffix
    Venue where (_.legacyid eqs 1) modify (_.tags addToSet "a")              toString() must_== query + """{ "$addToSet" : { "tags" : "a"}}""" + suffix
    Venue where (_.legacyid eqs 1) modify (_.popularity addToSet List(1L, 2L)) toString() must_== query + """{ "$addToSet" : { "popularity" : { "$each" : [ 1 , 2]}}}""" + suffix
    Venue where (_.legacyid eqs 1) modify (_.tags popFirst)                  toString() must_== query + """{ "$pop" : { "tags" : -1}}""" + suffix
    Venue where (_.legacyid eqs 1) modify (_.tags popLast)                   toString() must_== query + """{ "$pop" : { "tags" : 1}}""" + suffix
    Venue where (_.legacyid eqs 1) modify (_.tags pull "a")                  toString() must_== query + """{ "$pull" : { "tags" : "a"}}""" + suffix
    Venue where (_.legacyid eqs 1) modify (_.popularity pullAll List(2L, 3L))  toString() must_== query + """{ "$pullAll" : { "popularity" : [ 2 , 3]}}""" + suffix
    Venue where (_.legacyid eqs 1) modify (_.popularity at 0 inc 1)          toString() must_== query + """{ "$inc" : { "popularity.0" : 1}}""" + suffix
    // alternative syntax
    Venue where (_.legacyid eqs 1) modify (_.popularity idx 0 inc 1)         toString() must_== query + """{ "$inc" : { "popularity.0" : 1}}""" + suffix

    // Enumeration list
    OAuthConsumer modify (_.privileges addToSet ConsumerPrivilege.awardBadges) toString() must_== """db.oauthconsumers.update({ }, { "$addToSet" : { "privileges" : "Award badges"}}""" + suffix

    // BsonRecordField and BsonRecordListField with nested Enumeration
    val claims = List(VenueClaimBson.createRecord.userid(1).status(ClaimStatus.approved))
    Venue where (_.legacyid eqs 1) modify (_.claims setTo claims)         toString() must_== query + """{ "$set" : { "claims" : [ { "status" : "Approved" , "uid" : 1}]}}""" + suffix
    Venue where (_.legacyid eqs 1) modify (_.lastClaim setTo claims.head) toString() must_== query + """{ "$set" : { "lastClaim" : { "status" : "Approved" , "uid" : 1}}}""" + suffix

    // Map
    val m = Map("foo" -> 1L)
    val query3 = """db.tips.update({ "legid" : 1}, """
    Tip where (_.legacyid eqs 1) modify (_.counts setTo m)          toString() must_== query3 + """{ "$set" : { "counts" : { "foo" : 1}}}""" + suffix
    Tip where (_.legacyid eqs 1) modify (_.counts at "foo" setTo 3) toString() must_== query3 + """{ "$set" : { "counts.foo" : 3}}""" + suffix
    Tip where (_.legacyid eqs 1) modify (_.counts at "foo" inc 5)   toString() must_== query3 + """{ "$inc" : { "counts.foo" : 5}}""" + suffix
    Tip where (_.legacyid eqs 1) modify (_.counts at "foo" unset)   toString() must_== query3 + """{ "$unset" : { "counts.foo" : 1}}""" + suffix
    Tip where (_.legacyid eqs 1) modify (_.counts setTo Map("foo" -> 3, "bar" -> 5)) toString() must_== query3 + """{ "$set" : { "counts" : { "foo" : 3 , "bar" : 5}}}""" + suffix

    // Multiple updates
    Venue where (_.legacyid eqs 1) modify (_.venuename setTo "fshq") and (_.mayor_count setTo 3) toString() must_== query + """{ "$set" : { "mayor_count" : 3 , "venuename" : "fshq"}}""" + suffix
    Venue where (_.legacyid eqs 1) modify (_.venuename setTo "fshq") and (_.mayor_count inc 1)   toString() must_== query + """{ "$set" : { "venuename" : "fshq"} , "$inc" : { "mayor_count" : 1}}""" + suffix
    Venue where (_.legacyid eqs 1) modify (_.venuename setTo "fshq") and (_.mayor_count setTo 3) and (_.mayor_count inc 1) toString() must_== query + """{ "$set" : { "mayor_count" : 3 , "venuename" : "fshq"} , "$inc" : { "mayor_count" : 1}}""" + suffix
    Venue where (_.legacyid eqs 1) modify (_.popularity addToSet 3) and (_.tags addToSet List("a", "b")) toString() must_== query + """{ "$addToSet" : { "tags" : { "$each" : [ "a" , "b"]} , "popularity" : 3}}""" + suffix

    // Empty query
    Venue where (_.mayor in List()) modify (_.venuename setTo "fshq") toString() must_== "empty modify query"

    // Noop query
    Venue where (_.legacyid eqs 1) noop() toString() must_== query + "{ }" + suffix
    Venue where (_.legacyid eqs 1) noop() modify (_.venuename setTo "fshq") toString() must_== query + """{ "$set" : { "venuename" : "fshq"}}""" + suffix
    Venue where (_.legacyid eqs 1) noop() and (_.venuename setTo "fshq")    toString() must_== query + """{ "$set" : { "venuename" : "fshq"}}""" + suffix
  }

  @Test
  def testProduceACorrectSignatureString {
    val d1 = new DateTime(2010, 5, 1, 0, 0, 0, 0, DateTimeZone.UTC)
    val d2 = new DateTime(2010, 5, 2, 0, 0, 0, 0, DateTimeZone.UTC)
    val oid = new ObjectId

    // basic ops
    Venue where (_.mayor eqs 1)               signature() must_== """db.venues.find({ "mayor" : 0})"""
    Venue where (_.venuename eqs "Starbucks") signature() must_== """db.venues.find({ "venuename" : 0})"""
    Venue where (_.closed eqs true)           signature() must_== """db.venues.find({ "closed" : 0})"""
    Venue where (_._id eqs oid)               signature() must_== """db.venues.find({ "_id" : 0})"""
    VenueClaim where (_.status eqs ClaimStatus.approved) signature() must_== """db.venueclaims.find({ "status" : 0})"""
    Venue where (_.mayor_count gte 5) signature() must_== """db.venues.find({ "mayor_count" : { "$gte" : 0}})"""
    VenueClaim where (_.status neqs ClaimStatus.approved) signature() must_== """db.venueclaims.find({ "status" : { "$ne" : 0}})"""
    Venue where (_.legacyid in List(123L, 456L)) signature() must_== """db.venues.find({ "legid" : { "$in" : 0}})"""
    Venue where (_._id exists true) signature() must_== """db.venues.find({ "_id" : { "$exists" : 0}})"""
    Venue where (_.venuename startsWith "Starbucks") signature() must_== """db.venues.find({ "venuename" : 0})"""

    // list
    Venue where (_.tags all List("db", "ka"))    signature() must_== """db.venues.find({ "tags" : { "$all" : 0}})"""
    Venue where (_.tags in  List("db", "ka"))    signature() must_== """db.venues.find({ "tags" : { "$in" : 0}})"""
    Venue where (_.tags size 3)                  signature() must_== """db.venues.find({ "tags" : { "$size" : 0}})"""
    Venue where (_.tags contains "karaoke")      signature() must_== """db.venues.find({ "tags" : 0})"""
    Venue where (_.popularity contains 3)        signature() must_== """db.venues.find({ "popularity" : 0})"""
    Venue where (_.popularity at 0 eqs 3)        signature() must_== """db.venues.find({ "popularity.0" : 0})"""
    Venue where (_.categories at 0 eqs oid)      signature() must_== """db.venues.find({ "categories.0" : 0})"""
    Venue where (_.tags at 0 startsWith "kara")  signature() must_== """db.venues.find({ "tags.0" : 0})"""
    Venue where (_.tags idx 0 startsWith "kara") signature() must_== """db.venues.find({ "tags.0" : 0})"""

    // map
    Tip where (_.counts at "foo" eqs 3) signature() must_== """db.tips.find({ "counts.foo" : 0})"""

    // near
    Venue where (_.geolatlng near (39.0, -74.0, Degrees(0.2)))     signature() must_== """db.venues.find({ "latlng" : { "$near" : 0}})"""
    Venue where (_.geolatlng withinCircle(1.0, 2.0, Degrees(0.3))) signature() must_== """db.venues.find({ "latlng" : { "$within" : { "$center" : 0}}})"""
    Venue where (_.geolatlng withinBox(1.0, 2.0, 3.0, 4.0))        signature() must_== """db.venues.find({ "latlng" : { "$within" : { "$box" : 0}}})"""
    Venue where (_.geolatlng eqs (45.0, 50.0)) signature() must_== """db.venues.find({ "latlng" : 0})"""

    // id, date range
    Venue where (_._id before d2) signature()          must_== """db.venues.find({ "_id" : { "$lt" : 0}})"""
    Venue where (_.last_updated before d2) signature() must_== """db.venues.find({ "last_updated" : { "$lt" : 0}})"""

    // Case class list field
    Comment where (_.comments.unsafeField[Int]("z") eqs 123)           signature() must_== """db.comments.find({ "comments.z" : 0})"""
    Comment where (_.comments.unsafeField[String]("comment") eqs "hi") signature() must_== """db.comments.find({ "comments.comment" : 0})"""

    // Enumeration list
    OAuthConsumer where (_.privileges contains ConsumerPrivilege.awardBadges) signature() must_== """db.oauthconsumers.find({ "privileges" : 0})"""
    OAuthConsumer where (_.privileges at 0 eqs ConsumerPrivilege.awardBadges) signature() must_== """db.oauthconsumers.find({ "privileges.0" : 0})"""

    // Field type
    Venue where (_.legacyid hastype MongoType.String) signature() must_== """db.venues.find({ "legid" : { "$type" : 0}})"""

    // Modulus
    Venue where (_.legacyid mod (5, 1)) signature() must_== """db.venues.find({ "legid" : { "$mod" : 0}})"""

    // compound queries
    Venue where (_.mayor eqs 1) and (_.tags contains "karaoke") signature() must_== """db.venues.find({ "mayor" : 0 , "tags" : 0})"""
    Venue where (_.mayor eqs 1) and (_.mayor_count gt 3) and (_.mayor_count lt 5) signature() must_== """db.venues.find({ "mayor" : 0 , "mayor_count" : { "$lt" : 0 , "$gt" : 0}})"""

    // queries with no clauses
    metaRecordToQueryBuilder(Venue) signature() must_== "db.venues.find({ })"
    Venue orderDesc(_._id)          signature() must_== """db.venues.find({ }).sort({ "_id" : -1})"""

    // ordered queries
    Venue where (_.mayor eqs 1) orderAsc(_.legacyid) signature() must_== """db.venues.find({ "mayor" : 0}).sort({ "legid" : 1})"""
    Venue where (_.mayor eqs 1) orderDesc(_.legacyid) andAsc(_.userid) signature() must_== """db.venues.find({ "mayor" : 0}).sort({ "legid" : -1 , "userid" : 1})"""

    // select queries
    Venue where (_.mayor eqs 1) select(_.legacyid) signature() must_== """db.venues.find({ "mayor" : 0})"""

    // empty queries
    Venue where (_.mayor in List()) signature() must_== "empty query"
    Venue where (_.tags all List()) signature() must_== "empty query"
    Venue where (_.tags contains "karaoke") and (_.mayor in List()) signature() must_== "empty query"
    Venue where (_.mayor in List()) and (_.tags contains "karaoke") signature() must_== "empty query"

    // Scan should be the same as and/where
    Venue where (_.mayor eqs 1) scan (_.tags contains "karaoke") signature() must_== """db.venues.find({ "mayor" : 0 , "tags" : 0})"""
  }

  @Test
  def testFindAndModifyQueryShouldProduceACorrectJSONQueryString {
    Venue.where(_.legacyid eqs 1).findAndModify(_.venuename setTo "fshq").toString().must_==(
      """db.venues.findAndModify({ query: { "legid" : 1}, update: { "$set" : { "venuename" : "fshq"}}, new: false, upsert: false })""")
    Venue.where(_.legacyid eqs 1).orderAsc(_.popularity).findAndModify(_.venuename setTo "fshq").toString().must_==(
      """db.venues.findAndModify({ query: { "legid" : 1}, sort: { "popularity" : 1}, update: { "$set" : { "venuename" : "fshq"}}, new: false, upsert: false })""")
    Venue.where(_.legacyid eqs 1).select(_.mayor, _.closed).findAndModify(_.venuename setTo "fshq").toString().must_==(
      """db.venues.findAndModify({ query: { "legid" : 1}, update: { "$set" : { "venuename" : "fshq"}}, new: false, fields: { "mayor" : 1 , "closed" : 1}, upsert: false })""")
  }

  @Test
  def testOrQueryShouldProduceACorrectJSONQueryString {
    // Simple $or
    Venue.or(
        _.where(_.legacyid eqs 1),
        _.where(_.mayor eqs 2))
      .toString() must_== """db.venues.find({ "$or" : [ { "legid" : 1} , { "mayor" : 2}]})"""

    // Compound $or
    Venue.where(_.tags size 0)
         .or(
           _.where(_.legacyid eqs 1),
           _.where(_.mayor eqs 2))
      .toString() must_== """db.venues.find({ "tags" : { "$size" : 0} , "$or" : [ { "legid" : 1} , { "mayor" : 2}]})"""

    // $or with additional "and" clauses
    Venue.where(_.tags size 0)
         .or(
           _.where(_.legacyid eqs 1).and(_.closed eqs true),
           _.where(_.mayor eqs 2))
      .toString() must_== """db.venues.find({ "tags" : { "$size" : 0} , "$or" : [ { "legid" : 1 , "closed" : true} , { "mayor" : 2}]})"""

    // Nested $or
    Venue.or(
        _.where(_.legacyid eqs 1)
         .or(
            _.where(_.closed eqs true),
            _.where(_.closed exists false)),
        _.where(_.mayor eqs 2))
      .toString() must_== """db.venues.find({ "$or" : [ { "legid" : 1 , "$or" : [ { "closed" : true} , { "closed" : { "$exists" : false}}]} , { "mayor" : 2}]})"""

    // OrQuery syntax
    val q1 = Venue.where(_.legacyid eqs 1)
    val q2 = Venue.where(_.legacyid eqs 2)
    OrQuery(q1, q2) toString() must_==
        """db.venues.find({ "$or" : [ { "legid" : 1} , { "legid" : 2}]})"""
    OrQuery(q1, q2).and(_.mayor eqs 0) toString() must_==
        """db.venues.find({ "mayor" : 0 , "$or" : [ { "legid" : 1} , { "legid" : 2}]})"""
    OrQuery(q1, q2.or(_.where(_.closed eqs true), _.where(_.closed exists false))) toString() must_==
        """db.venues.find({ "$or" : [ { "legid" : 1} , { "legid" : 2 , "$or" : [ { "closed" : true} , { "closed" : { "$exists" : false}}]}]})"""
  }

  @Test
  def testHints {
    Venue where (_.legacyid eqs 1) hint (Venue.idIdx) toString()        must_== """db.venues.find({ "legid" : 1}).hint({ "_id" : 1})"""
    Venue where (_.legacyid eqs 1) hint (Venue.legIdx) toString()       must_== """db.venues.find({ "legid" : 1}).hint({ "legid" : -1})"""
    Venue where (_.legacyid eqs 1) hint (Venue.geoIdx) toString()       must_== """db.venues.find({ "legid" : 1}).hint({ "latlng" : "2d"})"""
    Venue where (_.legacyid eqs 1) hint (Venue.geoCustomIdx) toString() must_== """db.venues.find({ "legid" : 1}).hint({ "latlng" : "custom" , "tags" : 1})"""
  }

  @Test
  def testWhereOpt {
    val someId = Some(1L)
    val noId: Option[Long] = None
    val someList = Some(List(1L, 2L))
    val noList: Option[List[Long]] = None

    // whereOpt
    Venue.whereOpt(someId)(_.legacyid eqs _) toString() must_== """db.venues.find({ "legid" : 1})"""
    Venue.whereOpt(noId)(_.legacyid eqs _) toString() must_== """db.venues.find({ })"""
    Venue.whereOpt(someId)(_.legacyid eqs _).and(_.mayor eqs 2) toString() must_== """db.venues.find({ "legid" : 1 , "mayor" : 2})"""
    Venue.whereOpt(noId)(_.legacyid eqs _).and(_.mayor eqs 2) toString() must_== """db.venues.find({ "mayor" : 2})"""

    // whereOpt: lists
    Venue.whereOpt(someList)(_.legacyid in _) toString() must_== """db.venues.find({ "legid" : { "$in" : [ 1 , 2]}})"""
    Venue.whereOpt(noList)(_.legacyid in _) toString() must_== """db.venues.find({ })"""

    // whereOpt: enum
    val someEnum = Some(VenueStatus.open)
    val noEnum: Option[VenueStatus.type#Value] = None
    Venue.whereOpt(someEnum)(_.status eqs _) toString() must_== """db.venues.find({ "status" : "Open"})"""
    Venue.whereOpt(noEnum)(_.status eqs _) toString() must_== """db.venues.find({ })"""

    // whereOpt: date
    val someDate = Some(new DateTime(2010, 5, 1, 0, 0, 0, 0, DateTimeZone.UTC))
    val noDate: Option[DateTime] = None
    Venue.whereOpt(someDate)(_.last_updated after _) toString() must_== """db.venues.find({ "last_updated" : { "$gt" : { "$date" : "2010-05-01T00:00:00Z"}}})"""
    Venue.whereOpt(noDate)(_.last_updated after _) toString() must_== """db.venues.find({ })"""

    // andOpt
    Venue.where(_.mayor eqs 2).andOpt(someId)(_.legacyid eqs _) toString() must_== """db.venues.find({ "mayor" : 2 , "legid" : 1})"""
    Venue.where(_.mayor eqs 2).andOpt(noId)(_.legacyid eqs _) toString() must_== """db.venues.find({ "mayor" : 2})"""

    // scanOpt
    Venue.scanOpt(someId)(_.legacyid eqs _) toString() must_== """db.venues.find({ "legid" : 1})"""
    Venue.scanOpt(noId)(_.legacyid eqs _) toString() must_== """db.venues.find({ })"""
    Venue.scanOpt(someId)(_.legacyid eqs _).and(_.mayor eqs 2) toString() must_== """db.venues.find({ "legid" : 1 , "mayor" : 2})"""
    Venue.scanOpt(noId)(_.legacyid eqs _).and(_.mayor eqs 2) toString() must_== """db.venues.find({ "mayor" : 2})"""

    // iscanOpt
    Venue.iscanOpt(someId)(_.legacyid eqs _) toString() must_== """db.venues.find({ "legid" : 1})"""
    Venue.iscanOpt(noId)(_.legacyid eqs _) toString() must_== """db.venues.find({ })"""
    Venue.iscanOpt(someId)(_.legacyid eqs _).and(_.mayor eqs 2) toString() must_== """db.venues.find({ "legid" : 1 , "mayor" : 2})"""
    Venue.iscanOpt(noId)(_.legacyid eqs _).and(_.mayor eqs 2) toString() must_== """db.venues.find({ "mayor" : 2})"""

    // modify
    val q = Venue.where(_.legacyid eqs 1)
    val prefix = """db.venues.update({ "legid" : 1}, """
    val suffix = ", false, false)"

    q.modifyOpt(someId)(_.legacyid setTo _) toString() must_== prefix + """{ "$set" : { "legid" : 1}}""" + suffix
    q.modifyOpt(noId)(_.legacyid setTo _) toString() must_== prefix + """{ }""" + suffix
    q.modifyOpt(someEnum)(_.status setTo _) toString() must_== prefix + """{ "$set" : { "status" : "Open"}}""" + suffix
    q.modifyOpt(noEnum)(_.status setTo _) toString() must_== prefix + """{ }""" + suffix
  }

  @Test
  def testCommonSuperclassForPhantomTypes {
    def maybeLimit(legid: Long, limitOpt: Option[Int]) = {
      limitOpt match {
        case Some(limit) => Venue.where(_.legacyid eqs legid).limit(limit)
        case None => Venue.where(_.legacyid eqs legid)
      }
    }

    maybeLimit(1, None) toString() must_== """db.venues.find({ "legid" : 1})"""
    maybeLimit(1, Some(5)) toString() must_== """db.venues.find({ "legid" : 1}).limit(5)"""
  }

  @Test
  def thingsThatShouldntCompile {
    val compiler = new Compiler
    def check(code: String, shouldTypeCheck: Boolean = false): Unit = {
      compiler.typeCheck(code) aka "'%s' compiles!".format(code) must_== shouldTypeCheck
    }

    // For sanity
    // Venue where (_.legacyid eqs 3)
    check("""Venue where (_.legacyid eqs 3)""", shouldTypeCheck = true)

    // Basic operator and operand type matching
    check("""Venue where (_.legacyid eqs "hi")""")
    check("""Venue where (_.legacyid contains 3)""")
    check("""Venue where (_.tags contains 3)""")
    check("""Venue where (_.tags all List(3))""")
    check("""Venue where (_.geolatlng.unsafeField[String]("lat") eqs 3)""")
    check("""Venue where (_.closed eqs "false")""")
    check("""Venue where (_.tags < 3)""")
    check("""Venue where (_.tags size < 3)""")
    check("""Venue where (_.tags size "3")""")
    check("""Venue where (_.legacyid size 3)""")
    check("""Venue where (_.popularity at 3 eqs "hi")""")
    check("""Venue where (_.popularity at "a" eqs 3)""")

    // Modify
    check("""Venue where (_.legacyid eqs 1) modify (_.legacyid setTo "hi")""")
    // TODO: more

    // whereOpt
    check("""Venue.whereOpt(Some("hi"))(_.legacyid eqs _)""")

    // Foreign keys
    // first make sure that each type-safe foreign key works as expected
    check("""VenueClaim where (_.venueid eqs Venue.createRecord)""", true)
    check("""VenueClaim where (_.venueid neqs Venue.createRecord)""", true)
    check("""VenueClaim where (_.venueid in List(Venue.createRecord))""", true)
    check("""VenueClaim where (_.venueid nin List(Venue.createRecord))""", true)
    // now check that they reject invalid args
    check("""VenueClaim where (_.venueid eqs Tip.createRecord)""")
    check("""VenueClaim where (_.venueid neqs Tip.createRecord)""")
    check("""VenueClaim where (_.venueid in List(Tip.createRecord))""")
    check("""VenueClaim where (_.venueid nin List(Tip.createRecord))""")

    // Can't select array index
    check("""Venue where (_.legacyid eqs 1) select(_.tags at 0)""")

    //
    // Phantom type stuff
    //
    check("""Venue orderAsc(_.legacyid) orderAsc(_.closed)""")
    check("""Venue andAsc(_.legacyid)""")
    check("""Venue limit(1) limit(5)""")
    check("""Venue limit(1) fetch(5)""")
    check("""Venue limit(1) get()""")
    check("""Venue skip(3) skip(3)""")
    check("""Venue limit(10) count()""")
    check("""Venue skip(10) count()""")
    check("""Venue limit(10) countDistinct(_.legacyid)""")
    check("""Venue skip(10) countDistinct(_.legacyid)""")
    check("""Venue select(_.legacyid) select(_.closed)""")

    // select case class
    check("""Venue selectCase(_.legacyid, V2)""")
    check("""Venue selectCase(_.legacyid, _.tags, V2)""")

    // Index hints
    check("""Venue where (_.legacyid eqs 1) hint (Comment.idx1)""")

    // Modify
    check("""Venue limit(1) modify (_.legacyid setTo 1)""")
    check("""Venue skip(3) modify (_.legacyid setTo 1)""")
    check("""Venue select(_.legacyid) modify (_.legacyid setTo 1)""")

    // Noop
    check("""Venue limit(1) noop()""")
    check("""Venue skip(3) noop()""")
    check("""Venue select(_.legacyid) noop()""")

    // Delete
    check("""Venue limit(1) bulkDelete_!!""")
    check("""Venue skip(3) bulkDelete_!!""")
    check("""Venue select(_.legacyid) bulkDelete_!!""")

    // Or
    check("""Venue or (_ where (_.legacyid eqs 1), _ where (_.legacyid eqs 2)) or (_ where (_.closed eqs true), _ where (_.closed exists false))""")
    check("""Venue or (_ where (_.legacyid eqs 1), _ where (_.legacyid eqs 2) select (_.venuename))""")
    check("""Venue or (_ where (_.legacyid eqs 1), _ where (_.legacyid eqs 2) orderAsc (_.venuename))""")
    check("""Venue or (_ where (_.legacyid eqs 1), _ where (_.legacyid eqs 2) limit 10)""")
    check("""Venue or (_ where (_.legacyid eqs 1), _ where (_.legacyid eqs 2) skip 10)""")
    check("""OrQuery(Venue.where(_.legacyid eqs 1), Tip.where(_.legacyid eqs 2))""")
  }

  class Compiler {
    import java.io.{PrintWriter, Writer}
    import scala.io.Source
    import scala.tools.nsc.{Interpreter, InterpreterResults, Settings}

    class NullWriter extends Writer {
      override def close() = ()
      override def flush() = ()
      override def write(arr: Array[Char], x: Int, y: Int): Unit = ()
    }

    private val settings = new Settings
    val loader = manifest[Venue].erasure.getClassLoader
    settings.classpath.value = Source.fromURL(loader.getResource("app.class.path")).mkString
    settings.bootclasspath.append(Source.fromURL(loader.getResource("boot.class.path")).mkString)
    settings.deprecation.value = true // enable detailed deprecation warnings
    settings.unchecked.value = true // enable detailed unchecked warnings

    // This is deprecated in 2.9.x, but we need to use it for compatibility with 2.8.x
    private val interpreter =
      new Interpreter(
        settings,
        /**
         * It's a good idea to comment out this second parameter when adding or modifying
         * tests that shouldn't compile, to make sure that the tests don't compile for the
         * right reason.
         *
         * TODO(jorge): Consider comparing string output for each test to ensure the
         * actual compile error matches the expected compile error.
         **/
        new PrintWriter(new NullWriter()))

    interpreter.interpret("""import com.foursquare.rogue._""")
    interpreter.interpret("""import com.foursquare.rogue.Rogue._""")

    def typeCheck(code: String): Boolean = {
      val thunked = "() => { %s }".format(code)
      interpreter.interpret(thunked) match {
        case InterpreterResults.Success => true
        case InterpreterResults.Error => false
        case InterpreterResults.Incomplete => throw new Exception("Incomplete code snippet")
      }
    }
  }
}
