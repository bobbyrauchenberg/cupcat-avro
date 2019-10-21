package com.rauchenberg.avronaut.decoder

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.UUID

import cats.implicits._
import com.rauchenberg.avronaut.common.ReflectionHelpers._
import com.rauchenberg.avronaut.common._
import com.rauchenberg.avronaut.common.annotations.SchemaAnnotations.getAnnotations
import magnolia.{CaseClass, Magnolia, SealedTrait}
import org.apache.avro.generic.GenericRecord
import org.apache.avro.util.Utf8
import shapeless.{:+:, CNil, Coproduct, Inr}

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

trait DecoderBuilder[A] {

  def isString: Boolean = false

  def apply[B](value: B, failFast: Boolean): Results[A]

}

case class Decodable[A](decoder: DecoderBuilder[A])

object DecoderBuilder {

  type Typeclass[A] = DecoderBuilder[A]

  implicit def gen[A]: Typeclass[A] = macro Magnolia.gen[A]

  private def errorStr[C](param: String, value: C): String =
    "Decoding failed for param '".concat(param).concat("' with value '").concat(value + "' from the GenericRecord")

  def combine[A](ctx: CaseClass[Typeclass, A]): Typeclass[A] = new Typeclass[A] {

    val params = ctx.parameters.toArray

    override def apply[B](value: B, failFast: Boolean): Results[A] = {
      def iterateFailFast =
        params.toList.traverse { param =>
          val paramAnnotations = getAnnotations(param.annotations)
          val paramName        = paramAnnotations.name(param.label)

          value match {
            case genericRecord: GenericRecord =>
              val v = genericRecord.get(paramName)
              valueOrDefault(
                safeL {
                  v match {
                    case gr: GenericRecord =>
                      param.typeclass.apply(gr, failFast)
                    case _ =>
                      param.typeclass.apply(genericRecord.get(paramName), failFast)
                  }
                }.flatten,
                param.default,
                param.label,
                v
              )
            case _ => Nil.asLeft
          }
        }.map(ctx.rawConstruct(_))
          .leftMap(_ :+ Error("The value passed to the record decoder was: " + value.toString))

      def iterateAccumulating = {
        val it     = params.iterator
        var failed = false
        var cnt    = 0
        val arr    = new Array[Any](params.size)
        val errors = new ListBuffer[Error]()

        while (it.hasNext) {
          val param            = it.next()
          val paramAnnotations = getAnnotations(param.annotations)
          val paramName        = paramAnnotations.name(param.label)

          val res = value match {
            case genericRecord: GenericRecord =>
              val v = genericRecord.get(paramName)
              valueOrDefault(
                try {
                  v match {
                    case gr: GenericRecord =>
                      param.typeclass.apply(gr, failFast)
                    case _ =>
                      param.typeclass.apply(genericRecord.get(paramName), failFast)
                  }
                } catch {
                  case scala.util.control.NonFatal(_) =>
                    failed = true
                    Left(Nil) // needed to compile, but errors are accumulated in `errors`
                },
                param.default,
                param.label,
                v
              )
            case _ => Left(Nil)
          }
          res match {
            case Right(v) =>
              arr(cnt) = v
            case Left(l) =>
              errors.appendAll(l)
              failed = true
          }
          cnt += 1
        }
        if (!failed) Right(ctx.rawConstruct(arr))
        else Left(errors.toList :+ Error("The value passed to the record decoder was: " + value.toString))
      }

      if (failFast) {
        iterateFailFast
      } else {
        iterateAccumulating
      }
    }
  }

  def dispatch[A : WeakTypeTag](ctx: SealedTrait[Typeclass, A]): Typeclass[A] = new Typeclass[A] {

    val emptyFail = Left(Nil)

    def deriveEnum[B](value: B) =
      ctx.subtypes
        .find(_.typeName.short == value.toString)
        .map(st => ReflectionHelpers.toCaseObject[A](st.typeName.full))
        .fold[Results[A]](emptyFail)(Right(_))

    override def apply[B](value: B, failFast: Boolean): Results[A] =
      if (isEnum) deriveEnum(value)
      else {
        value match {
          case gr: GenericRecord =>
            ctx.subtypes
              .find(_.typeName.full == gr.getSchema.getFullName)
              .map(_.typeclass.apply(value, failFast))
              .getOrElse(emptyFail)
          case _ => deriveEnum(value)
        }
      }
  }

  private def valueOrDefault[A, B](value: Results[B], default: Option[B], paramName: String, origValue: A): Results[B] =
    (value, default) match {
      case (Right(value), _)        => Right(value)
      case (Left(_), Some(default)) => Right(default)
      case _                        => Left(List(Error(errorStr(paramName, origValue))))
    }

  def error[A](expected: String, actual: A): Either[Error, Nothing] = Left(Error(s"expected $expected, got $actual"))

  implicit val stringDecoderBuilder: DecoderBuilder[String] = new DecoderBuilder[String] {
    override def apply[B](value: B, failFast: Boolean): Results[String] = value match {
      case u: Utf8        => Right(u.toString)
      case s: String      => Right(s)
      case a: Array[Byte] => Right(new String(a))
    }
  }

  implicit val booleanDecoderBuilder: DecoderBuilder[Boolean] = new DecoderBuilder[Boolean] {
    override def apply[B](value: B, failFast: Boolean): Results[Boolean] =
      value match {
        case true  => Right(true)
        case false => Right(false)
      }
  }

  implicit val intDecoderBuilder: DecoderBuilder[Int] = new DecoderBuilder[Int] {
    override def apply[B](value: B, failFast: Boolean): Results[Int] =
      Right(value.toString.toInt)
  }

  implicit val longDecoderBuilder: DecoderBuilder[Long] = new DecoderBuilder[Long] {
    override def apply[B](value: B, failFast: Boolean): Results[Long] = value match {
      case l: Long => Right(l.toString.toLong)
    }
  }

  implicit val floatDecoderBuilder: DecoderBuilder[Float] = new DecoderBuilder[Float] {
    override def apply[B](value: B, failFast: Boolean): Results[Float] = value match {
      case f: Float => Right(f.toString.toFloat)
    }
  }

  implicit val doubleDecoderBuilder: DecoderBuilder[Double] = new DecoderBuilder[Double] {
    override def apply[B](value: B, failFast: Boolean): Results[Double] = value match {
      case d: Double => Right(d.toString.toDouble)
    }
  }

  implicit val bytesDecoderBuilder: DecoderBuilder[Array[Byte]] = new DecoderBuilder[Array[Byte]] {
    override def apply[B](value: B, failFast: Boolean): Results[Array[Byte]] =
      Right(value.asInstanceOf[Array[Byte]])
  }

  implicit def listDecoderBuilder[A : ClassTag](
      implicit elementDecoderBuilder: DecoderBuilder[A]): DecoderBuilder[List[A]] =
    new Typeclass[List[A]] {
      override def apply[B](value: B, failFast: Boolean): Results[List[A]] =
        value.asInstanceOf[java.util.List[A]].asScala.toList.traverse {
          elementDecoderBuilder(_, failFast)
        }
    }

  implicit def seqDecoderBuilder[A : ClassTag](
      implicit elementDecoderBuilder: DecoderBuilder[A]): DecoderBuilder[Seq[A]] = new DecoderBuilder[Seq[A]] {
    override def apply[B](value: B, failFast: Boolean): Results[Seq[A]] =
      listDecoderBuilder[A].apply[B](value, failFast)
  }

  implicit def vectorDecoderBuilder[A : ClassTag](
      implicit elementDecoderBuilder: DecoderBuilder[A]): DecoderBuilder[Vector[A]] =
    new DecoderBuilder[Vector[A]] {
      override def apply[B](value: B, failFast: Boolean): Results[Vector[A]] =
        listDecoderBuilder[A].apply[B](value, failFast).map(_.toVector)
    }

  implicit def setDecoderBuilder[A : ClassTag](
      implicit elementDecoderBuilder: DecoderBuilder[A]): DecoderBuilder[Set[A]] =
    new DecoderBuilder[Set[A]] {
      override def apply[B](value: B, failFast: Boolean): Results[Set[A]] =
        listDecoderBuilder[A].apply[B](value, failFast).map(_.toSet)
    }

  implicit def mapDecoderBuilder[A](implicit elementDecoderBuilder: DecoderBuilder[A]): DecoderBuilder[Map[String, A]] =
    new DecoderBuilder[Map[String, A]] {
      override def apply[B](value: B, failFast: Boolean): Results[Map[String, A]] =
        value
          .asInstanceOf[java.util.Map[String, A]]
          .asScala
          .toList
          .traverse {
            case (k, v) =>
              elementDecoderBuilder(v, failFast).map(k -> _)
          }
          .map(_.toMap)
    }

  implicit def offsetDateTimeDecoderBuilder: DecoderBuilder[OffsetDateTime] = new DecoderBuilder[OffsetDateTime] {
    override def apply[B](value: B, failFast: Boolean): Results[OffsetDateTime] =
      value match {
        case l: Long => Right(OffsetDateTime.ofInstant(Instant.ofEpochMilli(l), ZoneOffset.UTC))
      }
  }

  implicit def instantDecoderBuilder: DecoderBuilder[Instant] = new DecoderBuilder[Instant] {
    override def apply[B](value: B, failFast: Boolean): Results[Instant] = value match {
      case l: Long => Right(Instant.ofEpochMilli(l))
    }
  }

  implicit def uuidDecoderBuilder: DecoderBuilder[UUID] = new DecoderBuilder[UUID] {
    override def apply[B](value: B, failFast: Boolean): Results[UUID] = value match {
      case s: String if (s != null) => Right(java.util.UUID.fromString(s))
    }
  }

  implicit def optionDecoderBuilder[A](implicit valueDecoderBuilder: DecoderBuilder[A]): DecoderBuilder[Option[A]] =
    new DecoderBuilder[Option[A]] {
      override def apply[B](value: B, failFast: Boolean): Results[Option[A]] =
        if (value == null) Right(None)
        else
          valueDecoderBuilder(value, failFast).map(Option(_))
    }

  implicit def eitherDecoderBuilder[A, B](implicit lDecoderBuilder: DecoderBuilder[A],
                                          rDecoderBuilder: DecoderBuilder[B]): DecoderBuilder[Either[A, B]] =
    new DecoderBuilder[Either[A, B]] {
      override def apply[C](value: C, failFast: Boolean): Results[Either[A, B]] =
        if (rDecoderBuilder.isString) { //anything can decode to string, so run it 2nd
          safeL(lDecoderBuilder(value, failFast)).flatten match {
            case Right(v) => Right(Left(v))
            case Left(_) =>
              rDecoderBuilder(value, failFast) match {
                case Right(v) => Right(Right(v))
                case _        => Left(List(Error(s"couldn't decode either with value '$value'")))
              }
          }
        } else {
          safeL(rDecoderBuilder(value, failFast)).flatten match {
            case Right(v) => Right(Right(v))
            case Left(_) =>
              lDecoderBuilder(value, failFast) match {
                case Right(v) => Right(Left(v))
                case _        => Left(List(Error(s"couldn't decode either with value '$value'")))
              }
          }
        }

    }

  implicit object CNilDecoderBuilderValue extends DecoderBuilder[CNil] {
    override def apply[B](value: B, failFast: Boolean): Results[CNil] =
      List(Error("Should not have got to CNil")).asLeft
  }

  implicit def coproductDecoderBuilder[H, T <: Coproduct](implicit hDecoderBuilder: DecoderBuilder[H],
                                                          tDecoderBuilder: DecoderBuilder[T]): DecoderBuilder[H :+: T] =
    new DecoderBuilder[H :+: T] {
      type Ret = H :+: T
      override def apply[B](value: B, failFast: Boolean): Results[H :+: T] =
        safeL(hDecoderBuilder(value, failFast)).flatten match {
          case Left(_)  => tDecoderBuilder(value, failFast).map(Inr(_))
          case Right(r) => Right(Coproduct[H :+: T](r))
        }
    }

}
