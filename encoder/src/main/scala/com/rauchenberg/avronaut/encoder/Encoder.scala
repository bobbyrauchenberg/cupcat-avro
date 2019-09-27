package com.rauchenberg.avronaut.encoder

import cats.implicits._
import com.rauchenberg.avronaut.common.AvroType._
import com.rauchenberg.avronaut.common._
import com.rauchenberg.avronaut.schema.AvroSchema
import magnolia.{CaseClass, Magnolia}
import org.apache.avro.generic.GenericData

import scala.collection.JavaConverters._

trait Encoder[A] {

  def encode(value: A): Result[AvroType]

}

object Encoder {

  def apply[A](implicit encoder: Encoder[A]) = encoder

  type Typeclass[A] = Encoder[A]

  implicit def gen[A]: Typeclass[A] = macro Magnolia.gen[A]

  def encode[A](a: A)(implicit encoder: Encoder[A], schema: AvroSchema[A]): Either[Error, GenericData.Record] =
    for {
      s       <- schema.schema
      encoded <- encoder.encode(a)
      genRec  <- Parser(new GenericData.Record(s)).parse(encoded.asInstanceOf[AvroRecord])
    } yield genRec

  def combine[A](ctx: CaseClass[Typeclass, A])(implicit s: AvroSchema[A]): Typeclass[A] =
    new Typeclass[A] {
      override def encode(value: A): Result[AvroType] =
        s.schema.flatMap { schema =>
          schema.getFields.asScala.toList.traverse { field =>
            ctx.parameters.toList
              .find(_.label == field.name)
              .map(_.asRight)
              .getOrElse(Error("couldn't find param for schema field").asLeft)
              .flatMap(p => p.typeclass.encode(p.dereference(value)))
          }.map(AvroRecord(_))
        }
    }

  implicit val stringEncoder: Encoder[String] = toAvroString

  implicit val booleanEncoder: Encoder[Boolean] = toAvroBoolean

  implicit val intEncoder: Encoder[Int] = toAvroInt

  implicit val longEncoder: Encoder[Long] = toAvroLong

  implicit val floatEncoder: Encoder[Float] = toAvroFloat

  implicit val doubleEncoder: Encoder[Double] = toAvroDouble

  implicit val bytesEncoder: Encoder[Array[Byte]] = toAvroBytes

  implicit def listEncoder[A](implicit elementEncoder: Encoder[A]): Encoder[List[A]] =
    value => value.traverse(elementEncoder.encode(_)).map(AvroArray(_))

  implicit def optionEncoder[A](implicit elementEncoder: Encoder[A]): Encoder[Option[A]] =
    value => value.fold[Result[AvroType]](toAvroNull(null))(v => elementEncoder.encode(v)).map(AvroUnion(_))
}
