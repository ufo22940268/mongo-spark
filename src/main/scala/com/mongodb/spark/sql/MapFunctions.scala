/*
 * Copyright 2016 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.spark.sql

import java.sql.{Date, Timestamp}

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import org.apache.spark.sql.types.{ArrayType, NullType, StringType, TimestampType, _}

import org.bson._
import org.bson.types.Decimal128
import com.mongodb.spark.exceptions.MongoTypeConversionException
import com.mongodb.spark.sql.types.BsonCompatibility

private[spark] object MapFunctions {

  // scalastyle:off cyclomatic.complexity null
  def documentToRow(bsonDocument: BsonDocument, schema: StructType, requiredColumns: Array[String] = Array.empty[String]): Row = {
    val values: Array[(Any, StructField)] = schema.fields.map(field =>
      bsonDocument.containsKey(field.name) match {
        case true  => (convertToDataType(bsonDocument.get(field.name), field.dataType), field)
        case false => (null, field)
      })

    val requiredValues = requiredColumns.nonEmpty match {
      case true =>
        val requiredValueMap = Map(values.collect({
          case (rowValue, rowField) if requiredColumns.contains(rowField.name) =>
            (rowField.name, (rowValue, rowField))
        }): _*)
        requiredColumns.collect({ case name => requiredValueMap.getOrElse(name, null) })
      case false => values
    }
    new GenericRowWithSchema(requiredValues.map(_._1), DataTypes.createStructType(requiredValues.map(_._2)))
  }

  def rowToDocument(row: Row): BsonDocument = {
    val document = new BsonDocument()
    row.schema.fields.zipWithIndex.foreach({
      case (field, i) if row.isNullAt(i) => if (field.dataType == NullType) document.append(field.name, new BsonNull())
      case (field, i)                    => document.append(field.name, convertToBsonValue(row.get(i), field.dataType))
    })
    document
  }

  private def convertToDataType(element: BsonValue, elementType: DataType): Any = {
    (element.getBsonType, elementType) match {
      case (BsonType.DOCUMENT, mapType: MapType) => element.asDocument().asScala.map(kv => (kv._1, convertToDataType(kv._2, mapType.valueType))).toMap
      case (BsonType.ARRAY, arrayType: ArrayType) => element.asArray().getValues.asScala.map(convertToDataType(_, arrayType.elementType))
      case (BsonType.BINARY, BinaryType) => element.asBinary().getData
      case (BsonType.BOOLEAN, BooleanType) => element.asBoolean().getValue
      case (BsonType.DATE_TIME, DateType) => new Date(element.asDateTime().getValue)
      case (BsonType.DATE_TIME, TimestampType) => new Timestamp(element.asDateTime().getValue)
      case (BsonType.NULL, NullType) => null
      case (isBsonNumber(), DoubleType) => toDouble(element)
      case (isBsonNumber(), IntegerType) => toInt(element)
      case (isBsonNumber(), LongType) => toLong(element)
      case (isBsonNumber(), _) if elementType.typeName.startsWith("decimal") => toDecimal(element)
      case (notNull(), schema: StructType) => castToStructType(element, schema)
      case (_, StringType) => bsonValueToString(element)
      case _ =>
        if (element.isNull) {
          null
        } else {
          //throw new MongoTypeConversionException(s"Cannot cast ${element.getBsonType} into a $elementType (value: $element)")
          println(s"Cannot cast ${element.getBsonType} into a $elementType (value: $element)")
          null
        }
    }
  }

  private def convertToBsonValue(element: Any, elementType: DataType): BsonValue = {
    Try(elementTypeToBsonValue(element, elementType)) match {
      case Success(bsonValue)                        => bsonValue
      case Failure(ex: MongoTypeConversionException) => throw ex
      case Failure(e)                                => throw new MongoTypeConversionException(s"Cannot cast $element into a $elementType")
    }
  }

  private def bsonValueToString(element: BsonValue): String = {
    element.getBsonType match {
      case BsonType.STRING    => element.asString().getValue
      case BsonType.OBJECT_ID => element.asObjectId().getValue.toHexString
      case BsonType.INT64     => element.asInt64().getValue.toString
      case BsonType.INT32     => element.asInt32().getValue.toString
      case BsonType.DOUBLE    => element.asDouble().getValue.toString
      case BsonType.NULL      => null
      case _                  => BsonValueToJson(element)
    }
  }

  private def castToStructType(element: BsonValue, elementType: StructType): Any = {
    (element.getBsonType, elementType) match {
      case (BsonType.BINARY, BsonCompatibility.Binary()) =>
        BsonCompatibility.Binary(element.asInstanceOf[BsonBinary], elementType)
      case (BsonType.DOCUMENT, _) =>
        documentToRow(element.asInstanceOf[BsonDocument], elementType)
      case (BsonType.DB_POINTER, BsonCompatibility.DbPointer()) => BsonCompatibility.DbPointer(element.asInstanceOf[BsonDbPointer], elementType)
      case (BsonType.JAVASCRIPT, BsonCompatibility.JavaScript()) =>
        BsonCompatibility.JavaScript(element.asInstanceOf[BsonJavaScript], elementType)
      case (BsonType.JAVASCRIPT_WITH_SCOPE, BsonCompatibility.JavaScriptWithScope()) =>
        BsonCompatibility.JavaScriptWithScope(element.asInstanceOf[BsonJavaScriptWithScope], elementType)
      case (BsonType.MIN_KEY, BsonCompatibility.MinKey()) =>
        BsonCompatibility.MinKey(element.asInstanceOf[BsonMinKey], elementType)
      case (BsonType.MAX_KEY, BsonCompatibility.MaxKey()) =>
        BsonCompatibility.MaxKey(element.asInstanceOf[BsonMaxKey], elementType)
      case (BsonType.OBJECT_ID, BsonCompatibility.ObjectId()) =>
        BsonCompatibility.ObjectId(element.asInstanceOf[BsonObjectId], elementType)
      case (BsonType.REGULAR_EXPRESSION, BsonCompatibility.RegularExpression()) =>
        BsonCompatibility.RegularExpression(element.asInstanceOf[BsonRegularExpression], elementType)
      case (BsonType.SYMBOL, BsonCompatibility.Symbol()) =>
        BsonCompatibility.Symbol(element.asInstanceOf[BsonSymbol], elementType)
      case (BsonType.TIMESTAMP, BsonCompatibility.Timestamp()) =>
        BsonCompatibility.Timestamp(element.asInstanceOf[BsonTimestamp], elementType)
      case (BsonType.UNDEFINED, BsonCompatibility.Undefined()) =>
        BsonCompatibility.Undefined(element.asInstanceOf[BsonUndefined], elementType)
      case _ => throw new MongoTypeConversionException(s"Cannot cast ${element.getBsonType} into a $elementType (value: $element)")
    }
  }

  private def elementTypeToBsonValue(element: Any, elementType: DataType): BsonValue = {
    elementType match {
      case BinaryType           => new BsonBinary(element.asInstanceOf[Array[Byte]])
      case BooleanType          => new BsonBoolean(element.asInstanceOf[Boolean])
      case DateType             => new BsonDateTime(element.asInstanceOf[Date].getTime)
      case DoubleType           => new BsonDouble(element.asInstanceOf[Double])
      case IntegerType          => new BsonInt32(element.asInstanceOf[Int])
      case LongType             => new BsonInt64(element.asInstanceOf[Long])
      case StringType           => new BsonString(element.asInstanceOf[String])
      case TimestampType        => new BsonDateTime(element.asInstanceOf[Timestamp].getTime)
      case arrayType: ArrayType => arrayTypeToBsonValue(arrayType.elementType, element.asInstanceOf[Seq[_]])
      case schema: StructType   => castFromStructType(element.asInstanceOf[Row], schema)
      case mapType: MapType =>
        mapType.keyType match {
          case StringType => mapTypeToBsonValue(mapType.valueType, element.asInstanceOf[Map[String, _]])
          case _ => throw new MongoTypeConversionException(
            s"Cannot cast $element into a BsonValue. MapTypes must have keys of StringType for conversion into a BsonDocument"
          )
        }
      case _ if elementType.typeName.startsWith("decimal") =>
        val jBigDecimal = element match {
          case jDecimal: java.math.BigDecimal => jDecimal
          case _                              => element.asInstanceOf[BigDecimal].bigDecimal
        }
        new BsonDecimal128(new Decimal128(jBigDecimal))
      case _ =>
        throw new MongoTypeConversionException(s"Cannot cast $element into a BsonValue. $elementType has no matching BsonValue.")
    }
  }

  private def mapTypeToBsonValue(valueType: DataType, data: Map[String, Any]): BsonValue = {
    val internalData = valueType match {
      case subDocuments: StructType => data.map(kv => new BsonElement(kv._1, rowToDocument(kv._2.asInstanceOf[Row])))
      case subArray: ArrayType      => data.map(kv => new BsonElement(kv._1, arrayTypeToBsonValue(subArray.elementType, kv._2.asInstanceOf[Seq[Any]])))
      case _                        => data.map(kv => new BsonElement(kv._1, convertToBsonValue(kv._2, valueType)))
    }
    new BsonDocument(internalData.toList.asJava)
  }

  private def arrayTypeToBsonValue(elementType: DataType, data: Seq[Any]): BsonValue = {
    val internalData = elementType match {
      case subDocuments: StructType => data.map(x => rowToDocument(x.asInstanceOf[Row])).asJava
      case subArray: ArrayType      => data.map(x => arrayTypeToBsonValue(subArray.elementType, x.asInstanceOf[Seq[Any]])).asJava
      case _                        => data.map(x => convertToBsonValue(x, elementType)).asJava
    }
    new BsonArray(internalData)
  }

  private def castFromStructType(element: Row, dataType: StructType): BsonValue = {
    dataType match {
      case BsonCompatibility.ObjectId()            => BsonCompatibility.ObjectId(element)
      case BsonCompatibility.MinKey()              => BsonCompatibility.MinKey(element)
      case BsonCompatibility.MaxKey()              => BsonCompatibility.MaxKey(element)
      case BsonCompatibility.Timestamp()           => BsonCompatibility.Timestamp(element)
      case BsonCompatibility.JavaScript()          => BsonCompatibility.JavaScript(element)
      case BsonCompatibility.JavaScriptWithScope() => BsonCompatibility.JavaScriptWithScope(element)
      case BsonCompatibility.RegularExpression()   => BsonCompatibility.RegularExpression(element)
      case BsonCompatibility.Undefined()           => BsonCompatibility.Undefined(element)
      case BsonCompatibility.Binary()              => BsonCompatibility.Binary(element)
      case BsonCompatibility.Symbol()              => BsonCompatibility.Symbol(element)
      case BsonCompatibility.DbPointer()           => BsonCompatibility.DbPointer(element)
      case _                                       => rowToDocument(element)
    }
  }

  private object isBsonNumber {
    val bsonNumberTypes = Set(BsonType.INT32, BsonType.INT64, BsonType.DOUBLE, BsonType.DECIMAL128)
    def unapply(x: BsonType): Boolean = bsonNumberTypes.contains(x)
  }

  private def toInt(bsonValue: BsonValue): Int = {
    bsonValue.getBsonType match {
      case BsonType.DECIMAL128 => bsonValue.asDecimal128().decimal128Value().bigDecimalValue().intValue()
      case BsonType.INT32      => bsonValue.asInt32().intValue()
      case BsonType.INT64      => bsonValue.asInt64().intValue()
      case BsonType.DOUBLE     => bsonValue.asDouble().intValue()
      case _                   => throw new MongoTypeConversionException(s"Cannot cast ${bsonValue.getBsonType} into a Int")
    }
  }

  private def toLong(bsonValue: BsonValue): Long = {
    bsonValue.getBsonType match {
      case BsonType.DECIMAL128 => bsonValue.asDecimal128().decimal128Value().bigDecimalValue().longValue()
      case BsonType.INT32      => bsonValue.asInt32().longValue()
      case BsonType.INT64      => bsonValue.asInt64().longValue()
      case BsonType.DOUBLE     => bsonValue.asDouble().longValue()
      case _                   => throw new MongoTypeConversionException(s"Cannot cast ${bsonValue.getBsonType} into a Long")
    }
  }

  private def toDouble(bsonValue: BsonValue): Double = {
    bsonValue.getBsonType match {
      case BsonType.DECIMAL128 => bsonValue.asDecimal128().decimal128Value().bigDecimalValue().doubleValue()
      case BsonType.INT32      => bsonValue.asInt32().doubleValue()
      case BsonType.INT64      => bsonValue.asInt64().doubleValue()
      case BsonType.DOUBLE     => bsonValue.asDouble().doubleValue()
      case _                   => throw new MongoTypeConversionException(s"Cannot cast ${bsonValue.getBsonType} into a Double")
    }
  }

  private def toDecimal(bsonValue: BsonValue): BigDecimal = {
    bsonValue.getBsonType match {
      case BsonType.DECIMAL128 => bsonValue.asDecimal128().decimal128Value().bigDecimalValue()
      case BsonType.INT32      => BigDecimal(bsonValue.asInt32().intValue())
      case BsonType.INT64      => BigDecimal(bsonValue.asInt64().longValue())
      case BsonType.DOUBLE     => BigDecimal(bsonValue.asDouble().doubleValue())
      case _                   => throw new MongoTypeConversionException(s"Cannot cast ${bsonValue.getBsonType} into a BigDecimal")
    }
  }

  private object notNull {
    def unapply(x: BsonType): Boolean = x != BsonType.NULL
  }

  // scalastyle:on cyclomatic.complexity null
}
