import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

import scala.collection.{+:, mutable}

class RecWindow(length: Int, socket: DatagramChannel) {
	var arr = new Array[ByteBuffer](length)
	var firstBlk: Short = 0
	
	def apply(i: Int): ByteBuffer = {
		arr(i - firstBlk)
	}
	
	def recieve(blkNum: Short, buff: ByteBuffer): Int = {
		if (arr(blkNum - firstBlk) != null)
			-1
		else {
			arr(blkNum - firstBlk) = buff
			1
		}
	}
	
	def position: Int = {
		val op = arr.indexOf(null)
		if (op == -1) 4
		else op
	}
	
	def removeFirst: ByteBuffer = {
		val a = arr.head
		firstBlk = (firstBlk + 1.toShort).toShort
		arr = (arr.tail :+ null)
		a
	}
	
	def contains(blknum: Int): Boolean = {
		if (blknum < firstBlk)
			false
		else if (blknum > firstBlk + arr.length)
			false
		else if (arr(blknum) == null)
			false
		else
			true
	}
	
	def ack(blkNum: Short, key: Long): Unit = {
		socket.write(TftpClient.keyXor(key, TftpClient.ackPacket(blkNum)))
	}
	
	def empty: Boolean = {
		arr.head == null
	}
	
	def full: Boolean = {
		arr.last != null
	}
	
	def add(buffer: ByteBuffer, block: Int): Unit = {
		arr(block - firstBlk) = buffer
	}
	
	def foreach(value: ByteBuffer => Unit): Unit = {
		arr.foreach(value)
	}
	
	def inWindow(num: Int): Boolean = {
		if (num >= firstBlk && num <= firstBlk + arr.length)
			true
		else
			false
	}
	
	def slide: Seq[ByteBuffer] = {
		val out = Seq()
		val iter = arr.iterator
		while (iter.hasNext) {
			val next = iter.next
			if (next != null)
				out :+ next
			else
				return out
		}
		out
	}
}
