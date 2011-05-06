// Copyright 2011 Foursquare Labs Inc. All Rights Reserved.

package com.foursquare.rogue

import com.foursquare.rogue.MongoHelpers._
import com.mongodb.DBObject
import net.liftweb.mongodb.record._
import scala.collection.mutable.ListBuffer
import collection.immutable.List._

/////////////////////////////////////////////////////////////////////////////
// Phantom types
/////////////////////////////////////////////////////////////////////////////

abstract sealed class Ordered
abstract sealed class Unordered
abstract sealed class Selected
abstract sealed class Unselected
abstract sealed class Limited
abstract sealed class Unlimited
abstract sealed class Skipped
abstract sealed class Unskipped

/////////////////////////////////////////////////////////////////////////////
// Builders
/////////////////////////////////////////////////////////////////////////////


trait AbstractQuery[M <: MongoRecord[M], R, Ord, Sel, Lim, Sk] {
  def meta: MongoMetaRecord[M]
  def master: MongoMetaRecord[M]
  def where[F](clause: M => QueryClause[F]): AbstractQuery[M, R, Ord, Sel, Lim, Sk]
  def and[F](clause: M => QueryClause[F]): AbstractQuery[M, R, Ord, Sel, Lim, Sk]
  def scan[F](clause: M => QueryClause[F]): AbstractQuery[M, R, Ord, Sel, Lim, Sk]
  def iscan[F](clause: M => QueryClause[F]): AbstractQuery[M, R, Ord, Sel, Lim, Sk]

  def orderAsc[V](field: M => QueryField[V, M])(implicit ev: Ord =:= Unordered): AbstractQuery[M, R, Ordered, Sel, Lim, Sk]
  def orderDesc[V](field: M => QueryField[V, M])(implicit ev: Ord =:= Unordered): AbstractQuery[M, R, Ordered, Sel, Lim, Sk]
  def andAsc[V](field: M => QueryField[V, M])(implicit ev: Ord =:= Ordered): AbstractQuery[M, R, Ordered, Sel, Lim, Sk]
  def andDesc[V](field: M => QueryField[V, M])(implicit ev: Ord =:= Ordered): AbstractQuery[M, R, Ordered, Sel, Lim, Sk]

  def limit(n: Int)(implicit ev: Lim =:= Unlimited): AbstractQuery[M, R, Ord, Sel, Limited, Sk]
  def limitOpt(n: Option[Int])(implicit ev: Lim =:= Unlimited): AbstractQuery[M, R, Ord, Sel, Limited, Sk]
  def skip(n: Int)(implicit ev: Sk =:= Unskipped): AbstractQuery[M, R, Ord, Sel, Lim, Skipped]

  def count()(implicit ev1: Lim =:= Unlimited, ev2: Sk =:= Unskipped): Long
  def countDistinct[V](field: M => QueryField[V, M])(implicit ev1: Lim =:= Unlimited, ev2: Sk =:= Unskipped): Long

  def foreach(f: R => Unit): Unit
  def fetch(): List[R]
  def fetch(limit: Int)(implicit ev: Lim =:= Unlimited): List[R]
  def fetchBatch[T](batchSize: Int)(f: List[R] => List[T]): List[T]
  def get()(implicit ev: Lim =:= Unlimited): Option[R]
  def paginate(countPerPage: Int)(implicit ev1: Lim =:= Unlimited, ev2: Sk =:= Unskipped): BasePaginatedQuery[M, R]

  def bulkDelete_!!()(implicit ev1: Sel =:= Unselected, ev2: Lim =:= Unlimited, ev3: Sk =:= Unskipped): Unit

  def signature(): String
  def explain(): String

  def select[F1](f: M => SelectField[F1, M])(implicit ev: Sel =:= Unselected): AbstractQuery[M, F1, Ord, Selected, Lim, Sk]
  def select[F1, F2](f1: M => SelectField[F1, M], f2: M => SelectField[F2, M])(implicit ev: Sel =:= Unselected): AbstractQuery[M, (F1, F2), Ord, Selected, Lim, Sk]
  def select[F1, F2, F3](f1: M => SelectField[F1, M], f2: M => SelectField[F2, M], f3: M => SelectField[F3, M])(implicit ev: Sel =:= Unselected): AbstractQuery[M, (F1, F2, F3), Ord, Selected, Lim, Sk]
  def select[F1, F2, F3, F4](f1: M => SelectField[F1, M], f2: M => SelectField[F2, M], f3: M => SelectField[F3, M], f4: M => SelectField[F4, M])(implicit ev: Sel =:= Unselected): AbstractQuery[M, (F1, F2, F3, F4), Ord, Selected, Lim, Sk]
  def select[F1, F2, F3, F4, F5](f1: M => SelectField[F1, M], f2: M => SelectField[F2, M], f3: M => SelectField[F3, M], f4: M => SelectField[F4, M], f5: M => SelectField[F5, M])(implicit ev: Sel =:= Unselected): AbstractQuery[M, (F1, F2, F3, F4, F5), Ord, Selected, Lim, Sk]
  def select[F1, F2, F3, F4, F5, F6](f1: M => SelectField[F1, M], f2: M => SelectField[F2, M], f3: M => SelectField[F3, M], f4: M => SelectField[F4, M], f5: M => SelectField[F5, M], f6: M => SelectField[F6, M])(implicit ev: Sel =:= Unselected): AbstractQuery[M, (F1, F2, F3, F4, F5, F6), Ord, Selected, Lim, Sk]
}

class BaseQuery[M <: MongoRecord[M], R, Ord, Sel, Lim, Sk](
    override val meta: M with MongoMetaRecord[M],
    val lim: Option[Int],
    val sk: Option[Int],
    val condition: AndCondition,
    val order: Option[MongoOrder],
    val select: Option[MongoSelect[R, M]]) extends AbstractQuery[M, R, Ord, Sel, Lim, Sk] {

  // The meta field on the MongoMetaRecord (as an instance of MongoRecord)
  // points to the master MongoMetaRecord. This is here in case you have a
  // second MongoMetaRecord pointing to the slave.
  override lazy val master = meta.meta

  private def addClause[F](clause: M => QueryClause[F], expectedIndexBehavior: IndexBehavior.Value): AbstractQuery[M, R, Ord, Sel, Lim, Sk] = {
    clause(meta) match {
      case cl: EmptyQueryClause[_] => new BaseEmptyQuery[M, R, Ord, Sel, Lim, Sk]
      case cl => {
        new BaseQuery[M, R, Ord, Sel, Lim, Sk](meta, lim, sk, AndCondition(cl.withExpectedIndexBehavior(expectedIndexBehavior) :: condition.clauses), order, select)
      }
    }
  }

  override def where[F](clause: M => QueryClause[F]) = addClause(clause, expectedIndexBehavior = IndexBehavior.Index)
  override def and[F](clause: M => QueryClause[F]) = addClause(clause, expectedIndexBehavior = IndexBehavior.Index)
  override def iscan[F](clause: M => QueryClause[F]) = addClause(clause, expectedIndexBehavior = IndexBehavior.IndexScan)
  override def scan[F](clause: M => QueryClause[F]) = addClause(clause, expectedIndexBehavior = IndexBehavior.DocumentScan)

  override def orderAsc[V](field: M => QueryField[V, M])(implicit ev: Ord =:= Unordered) =
    new BaseQuery[M, R, Ordered, Sel, Lim, Sk](meta, lim, sk, condition, Some(MongoOrder(List((field(meta).field.name, true)))), select)
  override def orderDesc[V](field: M => QueryField[V, M])(implicit ev: Ord =:= Unordered) =
    new BaseQuery[M, R, Ordered, Sel, Lim, Sk](meta, lim, sk, condition, Some(MongoOrder(List((field(meta).field.name, false)))), select)
  override def andAsc[V](field: M => QueryField[V, M])(implicit ev: Ord =:= Ordered) =
    new BaseQuery[M, R, Ordered, Sel, Lim, Sk](meta, lim, sk, condition, Some(MongoOrder((field(meta).field.name, true) :: order.get.terms)), select)
  override def andDesc[V](field: M => QueryField[V, M])(implicit ev: Ord =:= Ordered) =
    new BaseQuery[M, R, Ordered, Sel, Lim, Sk](meta, lim, sk, condition, Some(MongoOrder((field(meta).field.name, false) :: order.get.terms)), select)

  override def limit(n: Int)(implicit ev: Lim =:= Unlimited) =
    new BaseQuery[M, R, Ord, Sel, Limited, Sk](meta, Some(n), sk, condition, order, select)
  override def limitOpt(n: Option[Int])(implicit ev: Lim =:= Unlimited) =
    new BaseQuery[M, R, Ord, Sel, Limited, Sk](meta, n, sk, condition, order, select)
  override def skip(n: Int)(implicit ev: Sk =:= Unskipped) =
    new BaseQuery[M, R, Ord, Sel, Lim, Skipped](meta, lim, Some(n), condition, order, select)

  private def parseDBObject(dbo: DBObject): R = select match {
    case Some(MongoSelect(fields, transformer)) =>
      val inst = fields.head.field.owner
      def setInstanceFieldFromDbo(fieldName: String) = inst.fieldByName(fieldName).open_!.setFromAny(dbo.get(fieldName))
      setInstanceFieldFromDbo("_id")
      transformer(fields.map(fld => fld(setInstanceFieldFromDbo(fld.field.name))))
    case None => meta.fromDBObject(dbo).asInstanceOf[R]
  }

  private def drainBuffer[A, B](from: ListBuffer[A], to: ListBuffer[B], f: List[A] => List[B], size: Int): Unit = {
    if (from.size >= size) {
      to ++= f(from.toList)
      from.clear
    }
  }

  override def count()(implicit ev1: Lim =:= Unlimited, ev2: Sk =:= Unskipped): Long =
    QueryExecutor.condition("count", this)(meta.count(_))
  override def countDistinct[V](field: M => QueryField[V, M])(implicit ev1: Lim =:= Unlimited, ev2: Sk =:= Unskipped): Long =
    QueryExecutor.condition("countDistinct", this)(meta.countDistinct(field(meta).field.name, _))
  override def foreach(f: R => Unit): Unit =
    QueryExecutor.query("find", this, None)(dbo => f(parseDBObject(dbo)))
  override def fetch(): List[R] = {
    val rv = new ListBuffer[R]
    QueryExecutor.query("find", this, None)(dbo => rv += parseDBObject(dbo))
    rv.toList
  }
  override def fetch(limit: Int)(implicit ev: Lim =:= Unlimited): List[R] =
    this.limit(limit).fetch()
  override def fetchBatch[T](batchSize: Int)(f: List[R] => List[T]): List[T] = {
    val rv = new ListBuffer[T]
    val buf = new ListBuffer[R]

    QueryExecutor.query("find", this, Some(batchSize)) { dbo =>
      buf += parseDBObject(dbo)
      drainBuffer(buf, rv, f, batchSize)
    }
    drainBuffer(buf, rv, f, 1)

    rv.toList
  }
  override def get()(implicit ev: Lim =:= Unlimited): Option[R] =
    fetch(1).headOption
  override def paginate(countPerPage: Int)(implicit ev1: Lim =:= Unlimited, ev2: Sk =:= Unskipped) = {
    val q = new BaseQuery[M, R, Ord, Sel, Unlimited, Unskipped](meta, lim, sk, condition, order, select)
    new BasePaginatedQuery(q, countPerPage)
  }

  // Always do modifications against master (not meta, which could point to slave)
  override def bulkDelete_!!()(implicit ev1: Sel =:= Unselected, ev2: Lim =:= Unlimited, ev3: Sk =:= Unskipped): Unit =
    QueryExecutor.condition("bulkDelete", this)(master.bulkDelete_!!(_))
  override def toString: String =
    MongoBuilder.buildString(this, None)
  override def signature(): String =
    MongoBuilder.buildSignature(this)
  override def explain(): String =
    QueryExecutor.explain("find", this)

  override def select[F1](f: M => SelectField[F1, M])(implicit ev: Sel =:= Unselected): BaseQuery[M, F1, Ord, Selected, Lim, Sk] = {
    val inst = meta.createRecord
    val fields = List(f(inst))
    val transformer = (xs: List[_]) => xs.head.asInstanceOf[F1]
    new BaseQuery(meta, lim, sk, condition, order, Some(MongoSelect(fields, transformer)))
  }
  
  override def select[F1, F2](f1: M => SelectField[F1, M], f2: M => SelectField[F2, M])(implicit ev: Sel =:= Unselected): BaseQuery[M, (F1, F2), Ord, Selected, Lim, Sk] = {
    val inst = meta.createRecord
    val fields = List(f1(inst), f2(inst))
    val transformer = (xs: List[_]) => (xs(0).asInstanceOf[F1], xs(1).asInstanceOf[F2])
    new BaseQuery(meta, lim, sk, condition, order, Some(MongoSelect(fields, transformer)))
  }

  override def select[F1, F2, F3](f1: M => SelectField[F1, M], f2: M => SelectField[F2, M], f3: M => SelectField[F3, M])(implicit ev: Sel =:= Unselected): BaseQuery[M, (F1, F2, F3), Ord, Selected, Lim, Sk] = {
    val inst = meta.createRecord
    val fields = List(f1(inst), f2(inst), f3(inst))
    val transformer = (xs: List[_]) => (xs(0).asInstanceOf[F1], xs(1).asInstanceOf[F2], xs(2).asInstanceOf[F3])
    new BaseQuery(meta, lim, sk, condition, order, Some(MongoSelect(fields, transformer)))
  }

  override def select[F1, F2, F3, F4](f1: M => SelectField[F1, M], f2: M => SelectField[F2, M], f3: M => SelectField[F3, M], f4: M => SelectField[F4, M])(implicit ev: Sel =:= Unselected): BaseQuery[M, (F1, F2, F3, F4), Ord, Selected, Lim, Sk] = {
    val inst = meta.createRecord
    val fields = List(f1(inst), f2(inst), f3(inst), f4(inst))
    val transformer = (xs: List[_]) => (xs(0).asInstanceOf[F1], xs(1).asInstanceOf[F2], xs(2).asInstanceOf[F3], xs(3).asInstanceOf[F4])
    new BaseQuery(meta, lim, sk, condition, order, Some(MongoSelect(fields, transformer)))
  }

  override def select[F1, F2, F3, F4, F5](f1: M => SelectField[F1, M], f2: M => SelectField[F2, M], f3: M => SelectField[F3, M], f4: M => SelectField[F4, M], f5: M => SelectField[F5, M])(implicit ev: Sel =:= Unselected): BaseQuery[M, (F1, F2, F3, F4, F5), Ord, Selected, Lim, Sk] = {
    val inst = meta.createRecord
    val fields = List(f1(inst), f2(inst), f3(inst), f4(inst), f5(inst))
    val transformer = (xs: List[_]) => (xs(0).asInstanceOf[F1], xs(1).asInstanceOf[F2], xs(2).asInstanceOf[F3], xs(3).asInstanceOf[F4], xs(4).asInstanceOf[F5])
    new BaseQuery(meta, lim, sk, condition, order, Some(MongoSelect(fields, transformer)))
  }

  def select[F1, F2, F3, F4, F5, F6](f1: M => SelectField[F1, M], f2: M => SelectField[F2, M], f3: M => SelectField[F3, M], f4: M => SelectField[F4, M], f5: M => SelectField[F5, M], f6: M => SelectField[F6, M])(implicit ev: Sel =:= Unselected): BaseQuery[M, (F1, F2, F3, F4, F5, F6), Ord, Selected, Lim, Sk] = {
    val inst = meta.createRecord
    val fields = List(f1(inst), f2(inst), f3(inst), f4(inst), f5(inst), f6(inst))
    val transformer = (xs: List[_]) => (xs(0).asInstanceOf[F1], xs(1).asInstanceOf[F2], xs(2).asInstanceOf[F3], xs(3).asInstanceOf[F4], xs(4).asInstanceOf[F5], xs(5).asInstanceOf[F6])
    new BaseQuery(meta, lim, sk, condition, order, Some(MongoSelect(fields, transformer)))
  }
}

class BaseEmptyQuery[M <: MongoRecord[M], R, Ord, Sel, Lim, Sk] extends AbstractQuery[M, R, Ord, Sel, Lim, Sk] {
  override lazy val meta = throw new Exception("tried to read meta field of an EmptyQuery")
  override lazy val master = throw new Exception("tried to read master field of an EmptyQuery")
  override def where[F](clause: M => QueryClause[F]) = this
  override def and[F](clause: M => QueryClause[F]) = this
  override def iscan[F](clause: M => QueryClause[F]) = this
  override def scan[F](clause: M => QueryClause[F]) = this

  override def orderAsc[V](field: M => QueryField[V, M])(implicit ev: Ord =:= Unordered) = new BaseEmptyQuery[M, R, Ordered, Sel, Lim, Sk]
  override def orderDesc[V](field: M => QueryField[V, M])(implicit ev: Ord =:= Unordered) = new BaseEmptyQuery[M, R, Ordered, Sel, Lim, Sk]
  override def andAsc[V](field: M => QueryField[V, M])(implicit ev: Ord =:= Ordered) = new BaseEmptyQuery[M, R, Ordered, Sel, Lim, Sk]
  override def andDesc[V](field: M => QueryField[V, M])(implicit ev: Ord =:= Ordered) = new BaseEmptyQuery[M, R, Ordered, Sel, Lim, Sk]

  override def limit(n: Int)(implicit ev: Lim =:= Unlimited) = new BaseEmptyQuery[M, R, Ord, Sel, Limited, Sk]
  override def limitOpt(n: Option[Int])(implicit ev: Lim =:= Unlimited) = new BaseEmptyQuery[M, R, Ord, Sel, Limited, Sk]
  override def skip(n: Int)(implicit ev: Sk =:= Unskipped) = new BaseEmptyQuery[M, R, Ord, Sel, Lim, Skipped]

  override def count()(implicit ev1: Lim =:= Unlimited, ev2: Sk =:= Unskipped): Long = 0
  override def countDistinct[V](field: M => QueryField[V, M])(implicit ev1: Lim =:= Unlimited, ev2: Sk =:= Unskipped): Long = 0

  override def foreach(f: R => Unit): Unit = ()
  override def fetch(): List[R] = Nil
  override def fetch(limit: Int)(implicit ev: Lim =:= Unlimited): List[R] = Nil
  override def fetchBatch[T](batchSize: Int)(f: List[R] => List[T]): List[T] = Nil
  override def get()(implicit ev: Lim =:= Unlimited): Option[R] = None
  override def paginate(countPerPage: Int)(implicit ev1: Lim =:= Unlimited, ev2: Sk =:= Unskipped) = {
    val emptyQuery = new BaseEmptyQuery[M, R, Ord, Sel, Unlimited, Unskipped]
    new BasePaginatedQuery(emptyQuery, countPerPage)
  }

  override def bulkDelete_!!()(implicit ev1: Sel =:= Unselected, ev2: Lim =:= Unlimited, ev3: Sk =:= Unskipped): Unit = ()

  override def toString = "empty query"
  override def signature = "empty query"
  override def explain = "{}"

  override def select[F1](f: M => SelectField[F1, M])(implicit ev: Sel =:= Unselected) = new BaseEmptyQuery[M, F1, Ord, Selected, Lim, Sk]
  override def select[F1, F2](f1: M => SelectField[F1, M], f2: M => SelectField[F2, M])(implicit ev: Sel =:= Unselected) = new BaseEmptyQuery[M, (F1, F2), Ord, Selected, Lim, Sk]
  override def select[F1, F2, F3](f1: M => SelectField[F1, M], f2: M => SelectField[F2, M], f3: M => SelectField[F3, M])(implicit ev: Sel =:= Unselected) = new BaseEmptyQuery[M, (F1, F2, F3), Ord, Selected, Lim, Sk]
  override def select[F1, F2, F3, F4](f1: M => SelectField[F1, M], f2: M => SelectField[F2, M], f3: M => SelectField[F3, M], f4: M => SelectField[F4, M])(implicit ev: Sel =:= Unselected) = new BaseEmptyQuery[M, (F1, F2, F3, F4), Ord, Selected, Lim, Sk]
  override def select[F1, F2, F3, F4, F5](f1: M => SelectField[F1, M], f2: M => SelectField[F2, M], f3: M => SelectField[F3, M], f4: M => SelectField[F4, M], f5: M => SelectField[F5, M])(implicit ev: Sel =:= Unselected) = new BaseEmptyQuery[M, (F1, F2, F3, F4, F5), Ord, Selected, Lim, Sk]
  override def select[F1, F2, F3, F4, F5, F6](f1: M => SelectField[F1, M], f2: M => SelectField[F2, M], f3: M => SelectField[F3, M], f4: M => SelectField[F4, M], f5: M => SelectField[F5, M], f6: M => SelectField[F6, M])(implicit ev: Sel =:= Unselected) = new BaseEmptyQuery[M, (F1, F2, F3, F4, F5, F6), Ord, Selected, Lim, Sk]
}

trait AbstractModifyQuery[M <: MongoRecord[M]] {
  def modify[F](clause: M => ModifyClause[F]): AbstractModifyQuery[M]
  def and[F](clause: M => ModifyClause[F]): AbstractModifyQuery[M]
  def noop(): AbstractModifyQuery[M]

  def updateMulti(): Unit
  def updateOne(): Unit
  def upsertOne(): Unit
}

class BaseModifyQuery[M <: MongoRecord[M]](val query: BaseQuery[M, _, _, _, _, _],
                                       val mod: MongoModify) extends AbstractModifyQuery[M] {

  private def addClause[F](clause: M => ModifyClause[F]) = {
    new BaseModifyQuery(query, MongoModify(clause(query.meta) :: mod.clauses))
  }

  override def modify[F](clause: M => ModifyClause[F]) = addClause(clause)
  override def and[F](clause: M => ModifyClause[F]) = addClause(clause)
  override def noop() = new BaseModifyQuery(query, MongoModify(Nil))

  // Always do modifications against master (not query.meta, which could point to slave)
  override def updateMulti(): Unit = QueryExecutor.modify("updateMulti", this)(query.master.updateMulti(_, _))
  override def updateOne(): Unit = QueryExecutor.modify("updateOne", this)(query.master.update(_, _))
  override def upsertOne(): Unit = QueryExecutor.modify("upsertOne", this)(query.master.upsert(_, _))

  override def toString = MongoBuilder.buildString(query, Some(mod))
}

class EmptyModifyQuery[M <: MongoRecord[M]] extends AbstractModifyQuery[M] {
  override def modify[F](clause: M => ModifyClause[F]) = this
  override def and[F](clause: M => ModifyClause[F]) = this
  override def noop() = this

  override def updateMulti(): Unit = ()
  override def updateOne(): Unit = ()
  override def upsertOne(): Unit = ()

  override def toString = "empty modify query"
}

class BasePaginatedQuery[M <: MongoRecord[M], R](q: AbstractQuery[M, R, _, _, Unlimited, Unskipped], val countPerPage: Int, val pageNum: Int = 1) {
  def copy() = new BasePaginatedQuery(q, countPerPage, pageNum)
  def setPage(p: Int) = if (p == pageNum) this else new BasePaginatedQuery(q, countPerPage, p)
  def setCountPerPage(c: Int) = if (c == countPerPage) this else new BasePaginatedQuery(q, c, pageNum)
  lazy val countAll: Long = q.count
  def fetch(): List[R] = q.skip(countPerPage * (pageNum - 1)).limit(countPerPage).fetch()
  def numPages = math.ceil(countAll.toDouble / countPerPage.toDouble).toInt max 1
}
