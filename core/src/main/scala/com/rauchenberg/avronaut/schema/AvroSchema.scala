package com.rauchenberg.avronaut.schema

import java.time.{Instant, OffsetDateTime}
import java.util.UUID

import cats.data.NonEmptyList
import cats.implicits._
import com.rauchenberg.avronaut.common
import com.rauchenberg.avronaut.common.ReflectionHelpers.isEnum
import com.rauchenberg.avronaut.common.annotations.SchemaAnnotations._
import com.rauchenberg.avronaut.common.{Error, Result}
import magnolia.{CaseClass, Magnolia, SealedTrait}
import shapeless.{:+:, CNil, Coproduct}

import scala.reflect.runtime.universe._

trait AvroSchema[A] {
  def schema: Result[AvroSchemaADT]
}

object AvroSchema {

  def apply[A](implicit avroSchema: AvroSchema[A]) = avroSchema

  def toSchema[A](implicit avroSchema: AvroSchema[A]): Either[common.Error, SchemaData] =
    avroSchema.schema.flatMap(Parser(_).parse)

  type Typeclass[A] = AvroSchema[A]

  implicit def gen[A]: Typeclass[A] = macro Magnolia.gen[A]

  def combine[A](ctx: CaseClass[Typeclass, A]): Typeclass[A] = new Typeclass[A] {

    val annotations       = getAnnotations(ctx.annotations)
    val doc               = annotations.doc
    val (name, namespace) = getNameAndNamespace(annotations, ctx.typeName.short, ctx.typeName.owner)

    override def schema: Result[AvroSchemaADT] =
      ctx.parameters.toList.traverse { param =>
        val paramAnnotations = getAnnotations(param.annotations)
        val paramName        = paramAnnotations.name(param.label)
        val sch              = param.typeclass.schema
        val ns               = paramAnnotations.flatMap(_.values.get(Namespace))

        (sch, ns) match {
          case (Right(s @ SchemaRecord(_, _, _, _)), Some(ns)) =>
            SchemaNamedField(paramName, paramAnnotations.doc, param.default, s.copy(namespace = ns)).asRight
          case (Right(s @ SchemaEnum(_, _, _, _)), Some(ns)) =>
            SchemaNamedField(paramName, paramAnnotations.doc, param.default, s.copy(namespace = ns)).asRight
          case (other, _) => other.map(SchemaNamedField(paramName, paramAnnotations.doc, param.default, _))
        }
      }.map(SchemaRecord(name, namespace, doc, _))
  }

  def dispatch[A : WeakTypeTag](ctx: SealedTrait[Typeclass, A]): Typeclass[A] = new Typeclass[A] {

    val anno              = getAnnotations(ctx.annotations)
    val (name, namespace) = getNameAndNamespace(anno, ctx.typeName.short, ctx.typeName.full)

    val subtypes = ctx.subtypes.toList.toNel

    override def schema: Result[AvroSchemaADT] =
      subtypes.fold[Result[AvroSchemaADT]](Error("Got an empty list of symbols building Enum or Union").asLeft)(
        st =>
          if (isEnum)
            SchemaEnum(name, namespace, anno.doc, st.map(_.typeName.short)).asRight
          else
            st.traverse(_.typeclass.schema).map(SchemaCoproduct(_)))

  }

  implicit val intSchema = new AvroSchema[Int] {
    override def schema: Result[AvroSchemaADT] = SchemaInt.asRight
  }

  implicit val longSchema = new AvroSchema[Long] {
    override def schema: Result[AvroSchemaADT] = SchemaLong.asRight
  }

  implicit val floatSchema = new AvroSchema[Float] {
    override def schema: Result[AvroSchemaADT] = SchemaFloat.asRight
  }

  implicit val doubleSchema = new AvroSchema[Double] {
    override def schema: Result[AvroSchemaADT] = SchemaDouble.asRight
  }

  implicit val stringSchema = new AvroSchema[String] {
    override def schema: Result[AvroSchemaADT] = SchemaString.asRight
  }

  implicit val booleanSchema = new AvroSchema[Boolean] {
    override def schema: Result[AvroSchemaADT] = SchemaBoolean.asRight
  }

  implicit val bytesSchema = new AvroSchema[Array[Byte]] {
    override def schema: Result[AvroSchemaADT] = SchemaBytes.asRight
  }

  implicit val nullSchema = new AvroSchema[Null] {
    override def schema: Result[AvroSchemaADT] = SchemaNull.asRight
  }

  implicit def listSchema[A](implicit elementSchema: AvroSchema[A]) = new AvroSchema[List[A]] {
    override def schema: Result[AvroSchemaADT] = elementSchema.schema.map(SchemaList(_))
  }

  implicit def seqSchema[A](implicit elementSchema: AvroSchema[A]) = new AvroSchema[Seq[A]] {
    override def schema: Result[AvroSchemaADT] = elementSchema.schema.map(SchemaList(_))
  }

  implicit def vectorSchema[A](implicit elementSchema: AvroSchema[A]) = new AvroSchema[Vector[A]] {
    override def schema: Result[AvroSchemaADT] = elementSchema.schema.map(SchemaList(_))
  }

  implicit def mapSchema[A](implicit elementSchema: AvroSchema[A]) = new AvroSchema[Map[String, A]] {
    override def schema: Result[AvroSchemaADT] = elementSchema.schema.map(SchemaMap(_))
  }

  implicit def optionSchema[A](implicit elementSchema: AvroSchema[A]) = new AvroSchema[Option[A]] {
    override def schema: Result[AvroSchemaADT] =
      elementSchema.schema.map(SchemaOption(_))
  }

  implicit def eitherSchema[A, B](implicit lElementSchema: AvroSchema[A], rElementSchema: AvroSchema[B]) =
    new AvroSchema[Either[A, B]] {
      override def schema: Result[AvroSchemaADT] = lElementSchema.schema.map2(rElementSchema.schema) {
        case (r, l) =>
          SchemaCoproduct(NonEmptyList.of(r, l))
      }
    }

  implicit def uuidSchema[A] = new Typeclass[UUID] {
    override def schema: Result[AvroSchemaADT] = SchemaUUID.asRight
  }

  implicit def offsetDateTimeSchema[A] = new Typeclass[OffsetDateTime] {
    override def schema: Result[AvroSchemaADT] = SchemaTimestampMillis.asRight
  }

  implicit def instantDateTimeSchema[A] = new Typeclass[Instant] {
    override def schema: Result[AvroSchemaADT] = SchemaTimestampMillis.asRight
  }

  implicit def cnilSchema[H](implicit hSchema: AvroSchema[H]) = new AvroSchema[H :+: CNil] {
    override def schema: Result[AvroSchemaADT] = hSchema.schema.map(v => SchemaCoproduct(NonEmptyList.of(v)))

  }

  implicit def coproductSchema[H, T <: Coproduct](implicit hSchema: AvroSchema[H], tSchema: AvroSchema[T]) =
    new AvroSchema[H :+: T] {
      override def schema: Result[AvroSchemaADT] =
        hSchema.schema
          .map2(tSchema.schema) {
            case (h, t) =>
              SchemaCoproduct(NonEmptyList.of(h, t))
          }
    }
}
