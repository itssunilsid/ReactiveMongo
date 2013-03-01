/*
 * Copyright 2013 Stephane Godbillon
 * @sgodbillon
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactivemongo.bson.buffer

import reactivemongo.bson._
import scala.util.{ Failure, Success, Try }

trait BufferHandler {
  def serialize(bson: BSONValue, buffer: WritableBuffer): WritableBuffer
  def deserialize(buffer: ReadableBuffer): Try[(String, BSONValue)]

  def write(buffer: WritableBuffer, document: BSONDocument) = {
    serialize(document, buffer)
  }

  def write(buffer: WritableBuffer, arr: BSONArray) = {
    serialize(arr, buffer)
  }

  def readDocument(buffer: ReadableBuffer): Try[BSONDocument]

  def writeDocument(document: BSONDocument, buffer: WritableBuffer): WritableBuffer

  def stream(buffer: ReadableBuffer): Stream[(String, BSONValue)] = {
    val elem = deserialize(buffer)
    if (elem.isSuccess)
      elem.get #:: stream(buffer)
    else Stream.empty
  }
}

object DefaultBufferHandler extends BufferHandler {
  sealed trait BufferWriter[B <: BSONValue] {
    def write(value: B, buffer: WritableBuffer): WritableBuffer
  }

  sealed trait BufferReader[B <: BSONValue] {
    def read(buffer: ReadableBuffer): B
  }

  sealed trait BufferRW[B <: BSONValue] extends BufferWriter[B] with BufferReader[B]

  val handlersByCode: Map[Int, BufferRW[_ <: BSONValue]] = Map(
    0x01 -> BSONDoubleBufferHandler,
    0x02 -> BSONStringBufferHandler,
    0x03 -> BSONDocumentBufferHandler,
    0x04 -> BSONArrayBufferHandler, // array
    0x05 -> BSONBinaryBufferHandler, // binary TODO
    0x06 -> BSONUndefinedBufferHandler, // undefined,
    0x07 -> BSONObjectIDBufferHandler, // objectid,
    0x08 -> BSONBooleanBufferHandler, // boolean
    0x09 -> BSONDateTimeBufferHandler, // datetime
    0x0A -> BSONNullBufferHandler, // null
    0x0B -> BSONRegexBufferHandler, // regex
    0x0C -> BSONDBPointerBufferHandler, // dbpointer
    0x0D -> BSONJavaScriptBufferHandler, // JS
    0x0E -> BSONSymbolBufferHandler, // symbol
    0x0F -> BSONJavaScriptWSBufferHandler, // JS with scope
    0x10 -> BSONIntegerBufferHandler,
    0x11 -> BSONTimestampBufferHandler, // timestamp,
    0x12 -> BSONLongBufferHandler, // long,
    0xFF -> BSONMinKeyBufferHandler, // min
    0x7F -> BSONMaxKeyBufferHandler) // max

  object BSONDoubleBufferHandler extends BufferRW[BSONDouble] {
    def write(value: BSONDouble, buffer: WritableBuffer): WritableBuffer = buffer.writeDouble(value.value)
    def read(buffer: ReadableBuffer): BSONDouble = BSONDouble(buffer.readDouble)
  }
  object BSONStringBufferHandler extends BufferRW[BSONString] {
    def write(value: BSONString, buffer: WritableBuffer): WritableBuffer = buffer.writeString(value.value)
    def read(buffer: ReadableBuffer): BSONString = BSONString(buffer.readString)
  }
  object BSONDocumentBufferHandler extends BufferRW[BSONDocument] {
    def write(doc: BSONDocument, buffer: WritableBuffer) = {
      val now = buffer.index
      buffer.writeInt(0)
      doc.elements.foreach { e =>
        buffer.writeByte(e._2.code.toByte)
        buffer.writeCString(e._1)
        serialize(e._2, buffer)
      }
      buffer.setInt(now, (buffer.index - now + 1))
      buffer.writeByte(0)
      buffer
    }
    def read(b: ReadableBuffer) = {

      val startIndex = b.index
      val length = b.readInt
      val buffer = b.slice(length - 4)
      b.discard(length - 4 + 1)
        def makeStream(): Stream[Try[(String, BSONValue)]] = {
          if (buffer.readable > 1) { // last is 0
            val code = buffer.readByte
            val name = buffer.readCString
            val elem = Try(name -> DefaultBufferHandler.handlersByCode.get(code).map(_.read(buffer)).get)
            elem #:: makeStream
          } else Stream.empty
        }
      val stream = makeStream
      stream.force // TODO remove
      new BSONDocument(stream)
    }
  }
  object BSONArrayBufferHandler extends BufferRW[BSONArray] {
    def write(array: BSONArray, buffer: WritableBuffer) = {
      val now = buffer.index
      buffer.writeInt(0)
      array.values.zipWithIndex.foreach { e =>
        buffer.writeByte(e._1.code.toByte)
        buffer.writeCString(e._2.toString)
        serialize(e._1, buffer)
      }
      buffer.setInt(now, (buffer.index - now + 1))
      buffer.writeByte(0)
      buffer
    }
    def read(b: ReadableBuffer) = {
      val startIndex = b.index
      val length = b.readInt
      val buffer = b.slice(length - 4)
      b.discard(length - 4 + 1)
        def makeStream(): Stream[Try[BSONValue]] = {
          if (buffer.readable > 1) { // last is 0
            val code = buffer.readByte
            val name = buffer.readCString
            val elem = Try(DefaultBufferHandler.handlersByCode.get(code).map(_.read(buffer)).get)
            elem #:: makeStream
          } else Stream.empty
        }
      val stream = makeStream
      stream.force // TODO remove
      new BSONArray(stream)
    }
  }
  object BSONBinaryBufferHandler extends BufferRW[BSONBinary] {
    def write(binary: BSONBinary, buffer: WritableBuffer) = {
      buffer.writeInt(binary.value.readable)
      buffer.writeByte(binary.subtype.value.toByte)
      val bin = binary.value.slice(binary.value.readable)
      buffer.writeBytes(bin.readArray(bin.readable)) // TODO
      buffer
    }
    def read(buffer: ReadableBuffer) = {
      val length = buffer.readInt
      val subtype = Subtype.apply(buffer.readByte)
      val bin = buffer.slice(length)
      buffer.discard(length)
      BSONBinary(bin, subtype)
    }
  }
  object BSONUndefinedBufferHandler extends BufferRW[BSONUndefined.type] {
    def write(undefined: BSONUndefined.type, buffer: WritableBuffer) = buffer
    def read(buffer: ReadableBuffer) = BSONUndefined
  }
  object BSONObjectIDBufferHandler extends BufferRW[BSONObjectID] {
    def write(objectId: BSONObjectID, buffer: WritableBuffer) = buffer writeBytes objectId.value
    def read(buffer: ReadableBuffer) = BSONObjectID(buffer.readArray(12))
  }
  object BSONBooleanBufferHandler extends BufferRW[BSONBoolean] {
    def write(boolean: BSONBoolean, buffer: WritableBuffer) = buffer writeByte (if (boolean.value) 1 else 0)
    def read(buffer: ReadableBuffer) = BSONBoolean(buffer.readByte == 0x01)
  }
  object BSONDateTimeBufferHandler extends BufferRW[BSONDateTime] {
    def write(dateTime: BSONDateTime, buffer: WritableBuffer) = buffer writeLong dateTime.value
    def read(buffer: ReadableBuffer) = BSONDateTime(buffer.readLong)
  }
  object BSONNullBufferHandler extends BufferRW[BSONNull.type] {
    def write(`null`: BSONNull.type, buffer: WritableBuffer) = buffer
    def read(buffer: ReadableBuffer) = BSONNull
  }
  object BSONRegexBufferHandler extends BufferRW[BSONRegex] {
    def write(regex: BSONRegex, buffer: WritableBuffer) = { buffer writeCString regex.value; buffer writeCString regex.flags }
    def read(buffer: ReadableBuffer) = BSONRegex(buffer.readCString, buffer.readCString)
  }
  object BSONDBPointerBufferHandler extends BufferRW[BSONDBPointer] {
    def write(pointer: BSONDBPointer, buffer: WritableBuffer) = buffer // TODO
    def read(buffer: ReadableBuffer) = BSONDBPointer(buffer.readCString, buffer.readArray(12))
  }
  object BSONJavaScriptBufferHandler extends BufferRW[BSONJavaScript] {
    def write(js: BSONJavaScript, buffer: WritableBuffer) = buffer writeString js.value
    def read(buffer: ReadableBuffer) = BSONJavaScript(buffer.readString)
  }
  object BSONSymbolBufferHandler extends BufferRW[BSONSymbol] {
    def write(symbol: BSONSymbol, buffer: WritableBuffer) = buffer writeString symbol.value
    def read(buffer: ReadableBuffer) = BSONSymbol(buffer.readString)
  }
  object BSONJavaScriptWSBufferHandler extends BufferRW[BSONJavaScriptWS] {
    def write(jsws: BSONJavaScriptWS, buffer: WritableBuffer) = buffer writeString jsws.value
    def read(buffer: ReadableBuffer) = BSONJavaScriptWS(buffer.readString)
  }
  object BSONIntegerBufferHandler extends BufferRW[BSONInteger] {
    def write(value: BSONInteger, buffer: WritableBuffer) = buffer writeInt value.value
    def read(buffer: ReadableBuffer): BSONInteger = BSONInteger(buffer.readInt)
  }
  object BSONTimestampBufferHandler extends BufferRW[BSONTimestamp] {
    def write(ts: BSONTimestamp, buffer: WritableBuffer) = buffer writeLong ts.value
    def read(buffer: ReadableBuffer) = BSONTimestamp(buffer.readLong)
  }
  object BSONLongBufferHandler extends BufferRW[BSONLong] {
    def write(long: BSONLong, buffer: WritableBuffer) = buffer writeLong long.value
    def read(buffer: ReadableBuffer) = BSONLong(buffer.readLong)
  }
  object BSONMinKeyBufferHandler extends BufferRW[BSONMinKey.type] {
    def write(b: BSONMinKey.type, buffer: WritableBuffer) = buffer
    def read(buffer: ReadableBuffer) = BSONMinKey
  }
  object BSONMaxKeyBufferHandler extends BufferRW[BSONMaxKey.type] {
    def write(b: BSONMaxKey.type, buffer: WritableBuffer) = buffer
    def read(buffer: ReadableBuffer) = BSONMaxKey
  }

  def serialize(bson: BSONValue, buffer: WritableBuffer): WritableBuffer = {
    handlersByCode.get(bson.code).get.asInstanceOf[BufferRW[BSONValue]].write(bson, buffer)
  }

  def deserialize(buffer: ReadableBuffer): Try[(String, BSONValue)] = Try {
    if (buffer.readable > 0) {
      val code = buffer.readByte
      buffer.readString -> handlersByCode.get(code).map(_.read(buffer)).get
    } else throw new NoSuchElementException("buffer can not be read, end of buffer reached")
  }

  def readDocument(buffer: ReadableBuffer): Try[BSONDocument] = Try {
    BSONDocumentBufferHandler.read(buffer)
  }

  def writeDocument(document: BSONDocument, buffer: WritableBuffer): WritableBuffer =
    serialize(document, buffer)
}

sealed trait BSONIterator extends Iterator[BSONElement] {
  val buffer: ReadableBuffer

  val startIndex = buffer.index
  val documentSize = buffer.readInt

  def next: BSONElement = {
    val code = buffer.readByte
    buffer.readString -> DefaultBufferHandler.handlersByCode.get(code).map(_.read(buffer)).get
  }

  def hasNext = buffer.index - startIndex + 1 < documentSize

  def mapped: Map[String, BSONElement] = {
    for (el <- this) yield (el._1, el)
  }.toMap
}

object BSONIterator {
  private[bson] def pretty(i: Int, it: Iterator[Try[BSONElement]]): String = {
    val prefix = (0 to i).map { i => "\t" }.mkString("")
    (for (tryElem <- it) yield {
      tryElem match {
        case Success(elem) => elem._2 match {
          case doc: BSONDocument => prefix + elem._1 + ": {\n" + pretty(i + 1, doc.stream.iterator) + "\n" + prefix + " }"
          case array: BSONArray => prefix + elem._1 + ": [\n" + pretty(i + 1, array.iterator) + "\n" + prefix + " ]"
          case _ => prefix + elem._1 + ": " + elem._2.toString
        }
        case Failure(e) => prefix + s"ERROR[${e.getMessage()}]"
      }
    }).mkString(",\n")
  }
  /** Makes a pretty String representation of the given [[reactivemongo.bson.BSONIterator]]. */
  def pretty(it: Iterator[Try[BSONElement]]): String = "{\n" + pretty(0, it) + "\n}"
}