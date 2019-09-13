package unit.decoder

import com.danielasfregola.randomdatagenerator.magnolia.RandomDataGenerator._
import com.rauchenberg.avronaut.decoder.Parser
import com.rauchenberg.avronaut.schema.AvroSchema
import org.apache.avro.generic.GenericData
import unit.decoder.utils.RunAssert._

class SimpleRecordSpec extends UnitSpecBase {

  "decoder" should {
    "decode a record with a string field" in {
      forAll { recordWithMultipleFields: RecordWithMultipleFields =>
        val writerSchema = AvroSchema[RecordWithMultipleFields].schema.value
        val readerSchema = AvroSchema[ReaderStringRecord].schema.value

        val record = new GenericData.Record(writerSchema)
        record.put(0, recordWithMultipleFields.field1)
        record.put(1, recordWithMultipleFields.field2)
        record.put(2, recordWithMultipleFields.field3)

        Parser.decode[ReaderStringRecord](readerSchema, record) should beRight(
          ReaderStringRecord(recordWithMultipleFields.field2))
      }
    }
    "decode a record with a boolean field" in {
      forAll { record: BooleanRecord =>
        runAssert(record.field, record)
      }
    }

    "decode a record with a boolean field with a reader schema" in {
      forAll { recordWithMultipleFields: RecordWithMultipleFields =>
        val writerSchema = AvroSchema[RecordWithMultipleFields].schema.value
        val readerSchema = AvroSchema[ReaderBooleanRecord].schema.value

        val record = new GenericData.Record(writerSchema)
        record.put(0, recordWithMultipleFields.field1)
        record.put(1, recordWithMultipleFields.field2)
        record.put(2, recordWithMultipleFields.field3)

        Parser.decode[ReaderBooleanRecord](readerSchema, record) should beRight(
          ReaderBooleanRecord(recordWithMultipleFields.field1))
      }
    }

    "decode a record with an int field" in {
      forAll { record: IntRecord =>
        runAssert(record.field, record)
      }
    }
    "decode a record with a long field" in {
      forAll { record: LongRecord =>
        runAssert(record.field, record)
      }
    }
    "decode a record with a float field" in {
      forAll { record: FloatRecord =>
        runAssert(record.field, record)
      }
    }
    "decode a record with a double field" in {
      forAll { record: DoubleRecord =>
        runAssert(record.field, record)
      }
    }

    "handle simple nested case classes" in {
      forAll { outer: Outer =>
        val schema      = AvroSchema[Outer]
        val innerSchema = AvroSchema[Inner]

        val outRec = new GenericData.Record(schema.schema.value)
        val inRec  = new GenericData.Record(innerSchema.schema.value)

        inRec.put(0, outer.outerField2.innerField1)
        inRec.put(1, outer.outerField2.innerField2)
        inRec.put(2, outer.outerField2.duplicateFieldName)

        outRec.put(0, outer.outerField1)
        outRec.put(1, inRec)
        outRec.put(2, outer.duplicateFieldName)

        Parser.decode[Outer](schema.schema.value, outRec) should beRight(outer)
      }

    }

    "use a different reader schema to the writer schema" in {}

  }

  case class BooleanRecord(field: Boolean)
  case class ReaderBooleanRecord(field1: Boolean)

  case class RecordWithMultipleFields(field1: Boolean, field2: String, field3: Int)
  case class ReaderStringRecord(field2: String)

  case class IntRecord(field: Int)
  case class LongRecord(field: Long)
  case class FloatRecord(field: Float)
  case class DoubleRecord(field: Double)
  case class StringRecord(field: String)
  case class BytesRecord(field: Array[Byte])
  case class NestedRecord(field: IntRecord)

  case class Inner(innerField1: String, innerField2: Int, duplicateFieldName: String)
  case class Outer(outerField1: String, outerField2: Inner, duplicateFieldName: String)

}
