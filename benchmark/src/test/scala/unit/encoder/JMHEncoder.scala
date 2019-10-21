package unit.encoder

import java.util.concurrent.TimeUnit

import com.rauchenberg.avronaut.Codec
import com.rauchenberg.avronaut.Codec._
import com.rauchenberg.avronaut.common.Results
import com.rauchenberg.avronaut.encoder.Encoder
import com.sksamuel.avro4s.{DefaultFieldMapper, Encoder => Avro4SEncoder}
import org.apache.avro.generic.GenericRecord
import org.openjdk.jmh.annotations._

trait AvronautEncodingManyStrings extends EncoderBenchmarkDataManyStrings {

  implicit val codec = Codec[RecordWithNestedCaseClasses]

  @Benchmark
  def runNestedEncoder: List[Results[GenericRecord]] =
    dataSet.map { element =>
      element.encode
    }
}

trait Avro4SEncodingManyStrings extends EncoderBenchmarkDataManyStrings {

  implicit val encoder = Avro4SEncoder[RecordWithNestedCaseClasses]
  val schema           = writerSchema.data.right.get.schema

  @Benchmark
  def runNestedEncoder: List[AnyRef] =
    dataSet.map { element =>
      encoder.encode(element, schema, DefaultFieldMapper)
    }
}

trait AvronautEncodingNoStrings extends EncoderBenchmarkDataNoStrings {

  implicit val codec = Codec[RecordWithNestedCaseClasses]

  @Benchmark
  def runNestedEncoder: List[Results[GenericRecord]] =
    dataSet.map(_.encode)
}

trait Avro4SRecordEncodingNoStrings extends EncoderBenchmarkDataNoStrings {
  implicit val encoder = Avro4SEncoder[RecordWithNestedCaseClasses]
  val schema           = writerSchema.data.right.get.schema

  @Benchmark
  def runNestedEncoder: List[AnyRef] =
    dataSet.map { element =>
      encoder.encode(element, schema, DefaultFieldMapper)
    }
}

trait AvronautSimpleRecord extends EncoderBenchmarkSimpleRecord {

  implicit val codec = Codec[SimpleRecord]

  @Benchmark
  def runNestedEncoder: List[Results[GenericRecord]] =
    dataSet.map(_.encode)
}

trait Avro4SSimpleRecord extends EncoderBenchmarkSimpleRecord {
  implicit val encoder = Avro4SEncoder[SimpleRecord]
  val schema           = writerSchema.data.right.get.schema

  @Benchmark
  def runNestedEncoder: List[Any] =
    dataSet.map { element =>
      encoder.encode(element, schema, DefaultFieldMapper)
    }
}

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class AvronautNestedEncodingBenchmarkManyStrings extends AvronautEncodingManyStrings

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class Avro4SNestedEncodingBenchmarkManyStrings extends Avro4SEncodingManyStrings

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class AvronautNestedEncodingBenchmarkNoStrings extends AvronautEncodingNoStrings

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class Avro4SNestedEncodingBenchmarkNoStrings extends Avro4SRecordEncodingNoStrings

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class AvronautEncodingBenchmarkSimpleRecord extends AvronautSimpleRecord

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class Avro4SEncodingBenchmarkSimpleRecord extends Avro4SSimpleRecord
