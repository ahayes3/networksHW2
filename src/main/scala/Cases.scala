import java.nio.ByteBuffer

import scala.collection.mutable

abstract class TftpPacket {
	def opcode: Short
	
	def toByteBuffer: ByteBuffer
}

case class RrqPacket(filename: String, options: mutable.HashMap[String, String]) extends TftpPacket {
	def opcode = 1
	
	override def toByteBuffer: ByteBuffer = {
		var len: Int = 0
		for (k <- options.keySet) {
			len += k.length
			len += options.getOrElse(k, "").length
			len += 2
		}
		val output = ByteBuffer.allocate(1 + filename.length + len)
		output.putShort(opcode)
		output.put(filename.getBytes)
		output.put(0.toByte)
		options.foreach(p => {
			output.put(p._1.getBytes)
			output.put(0.toByte)
			output.put(p._2.getBytes)
			output.put(0.toByte)
		})
		output
	}
}

case class WrqPacket(filename: String, options: mutable.HashMap[String, String]) extends TftpPacket {
	def opcode = 2
	
	override def toByteBuffer: ByteBuffer = {
		val output = ByteBuffer.allocate(4 + filename.length)
		output.putShort(opcode)
		output.put(filename.getBytes)
		output.put(0.toByte)
		
		options.foreach(p => {
			output.put(p._1.getBytes)
			output.put(0.toByte)
			output.put(p._2.getBytes)
			output.put(0.toByte)
		})
		output
	}
}

case class DataPacket(blknum: Short, data: ByteBuffer) extends TftpPacket {
	def opcode = 3
	
	override def toByteBuffer: ByteBuffer = {
		val output = ByteBuffer.allocate(4 + data.capacity())
		output.putShort(opcode)
		output.putShort(blknum)
		output.put(data)
		output
	}
}

case class AckPacket(blknum: Short) extends TftpPacket {
	def opcode = 4
	
	override def toByteBuffer: ByteBuffer = {
		val output = ByteBuffer.allocate(4)
		output.putShort(opcode)
		output.putShort(blknum)
		output
	}
}

case class ErrPacket(errcode: Short, errmsg: String) extends TftpPacket {
	def opcode = 5
	
	override def toByteBuffer: ByteBuffer = {
		val output = ByteBuffer.allocate(5 + errmsg.length)
		output.putShort(opcode)
		output.putShort(errcode)
		output.put(errmsg.getBytes)
		output.put(0.toByte)
		output
	}
}

case class OackPacket(options: mutable.HashMap[String, String]) extends TftpPacket {
	def opcode = 6
	
	override def toByteBuffer: ByteBuffer = {
		var len: Int = 0
		for (k <- options.keySet) {
			len += k.length
			len += options.getOrElse(k, "").length
			len += 2
		}
		val output = ByteBuffer.allocate(2 + len)
		output.putShort(opcode)
		options.foreach(p => {
			output.put(p._1.getBytes)
			output.put(0.toByte)
			output.put(p._2.getBytes)
			output.put(0.toByte)
		})
		output
	}
}