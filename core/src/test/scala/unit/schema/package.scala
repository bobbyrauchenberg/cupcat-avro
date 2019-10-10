package unit

import cats.scalatest.EitherValues
import com.rauchenberg.avronaut.schema.AvroSchema

package object schema extends EitherValues {

  def schemaAsString[A : AvroSchema] = AvroSchema.toSchema[A].value.schema.toString

}
