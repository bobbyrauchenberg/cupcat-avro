package com.rauchenberg.avronaut.common

import scalaz._
import Scalaz._
import matryoshka.data.Fix
import org.apache.avro.Schema

sealed trait AvroF[+A]
final case object AvroNullF                                                             extends AvroF[Nothing]
final case class AvroIntF(value: Int)                                                   extends AvroF[Nothing]
final case class AvroLongF(value: Long)                                                 extends AvroF[Nothing]
final case class AvroFloatF(value: Float)                                               extends AvroF[Nothing]
final case class AvroDoubleF(value: Double)                                             extends AvroF[Nothing]
final case class AvroBooleanF(value: Boolean)                                           extends AvroF[Nothing]
final case class AvroStringF(value: String)                                             extends AvroF[Nothing]
final case class AvroRecordF[A](schema: Schema, value: List[A], isTop: Boolean = false) extends AvroF[A]
final case class AvroEnumF[B](value: String)                                            extends AvroF[Nothing]
final case class AvroUnionF[A](value: A)                                                extends AvroF[A]
final case class AvroArrayF[A](schema: Schema, value: List[A])                          extends AvroF[A]
final case class AvroMapF[A](value: List[(String, A)])                                  extends AvroF[A]
final case class AvroBytesF(value: Array[Byte])                                         extends AvroF[Nothing]
final case class AvroLogicalF[A](value: A)                                              extends AvroF[A]

object AvroF {
  type AvroFix = Fix[AvroF]

  implicit val AvroFTraverse = new Traverse[AvroF] {
    override def traverseImpl[G[_], A, B](fa: AvroF[A])(f: A => G[B])(implicit G: Applicative[G]): G[AvroF[B]] =
      fa match {
        case AvroNullF                         => G.pure(AvroNullF)
        case a @ AvroIntF(_)                   => G.pure(a)
        case a @ AvroLongF(_)                  => G.pure(a)
        case a @ AvroFloatF(_)                 => G.pure(a)
        case a @ AvroDoubleF(_)                => G.pure(a)
        case a @ AvroBooleanF(_)               => G.pure(a)
        case a @ AvroStringF(_)                => G.pure(a)
        case AvroRecordF(schema, value, isTop) => G.map(value.traverse(f))(AvroRecordF(schema, _, isTop))
        case a @ AvroEnumF(_)                  => G.pure(a)
        case AvroUnionF(value)                 => G.map(f(value))(AvroUnionF(_))
        case AvroArrayF(schema, value)         => G.map(value.traverse(f))(AvroArrayF(schema, _))
        case AvroMapF(value) => {
          G.map(value.map {
            case (s, a) =>
              G.map(f(a))(v => (s, v))
          }.sequence)(AvroMapF(_))
        }
        case a @ AvroBytesF(value) => G.pure(a)
        case AvroLogicalF(value)   => G.map(f(value))(AvroLogicalF(_))
      }

    override def map[A, B](fa: AvroF[A])(f: A => B): AvroF[B] = fa match {
      case AvroNullF                         => AvroNullF
      case a @ AvroIntF(_)                   => a
      case a @ AvroLongF(_)                  => a
      case a @ AvroFloatF(_)                 => a
      case a @ AvroDoubleF(_)                => a
      case a @ AvroBooleanF(_)               => a
      case a @ AvroStringF(_)                => a
      case AvroRecordF(schema, value, isTop) => AvroRecordF(schema, value.map(f), isTop)
      case a @ AvroEnumF(_)                  => a
      case AvroUnionF(value)                 => AvroUnionF(f(value))
      case AvroArrayF(schema, value)         => AvroArrayF(schema, value.map(f))
      case AvroMapF(value)                   => AvroMapF { value.map { case (k, v) => k -> f(v) } }
      case a @ AvroBytesF(value)             => a
      case a @ AvroLogicalF(value)           => AvroLogicalF(f(value))
    }

  }
}
