import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

import scala.collection.mutable

class SlidingWindow(length: Int, socket: DatagramChannel) {
	var arr = new Array[ByteBuffer](length)
	var sentTime = new Array[Long](length)
	var waitMult: Array[Int] = (for (i <- 0 until length) yield 1).toArray
	var acked = new Array[Boolean](length)
	var firstBlk: Short = 0
	val stdWait = 500
	
	def apply(i: Int): ByteBuffer = {
		arr(i - firstBlk)
	}
	
	def getTime(blksize: Int): Long = {
		sentTime(blksize - firstBlk)
	}
	
	def position: Int = {
		val op = arr.indexOf(null)
		if (op == -1) 4
		else op
	}
	
	def removeFirst: ByteBuffer = {
		val a = arr.head
		firstBlk = (firstBlk + 1).toShort
		arr = (arr.tail :+ null)
		acked = acked.tail :+ false
		sentTime = sentTime.tail :+ 0
		waitMult = waitMult.tail :+ 1
		a
	}
	
	def ack(blkNum: Int): Unit = {
		if(acked.indices.contains(blkNum - firstBlk))
			acked(blkNum - firstBlk) = true
	}
	
	def isAcked(blkNum: Int): Boolean = {
		if (blkNum - firstBlk < arr.length)
			acked(blkNum - firstBlk)
		else
			false
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
	
	def take(list: mutable.ArrayBuffer[ByteBuffer]): Int = {
		var ctr = 0
		val last = arr.lastIndexWhere(_ != null)
		if(last != arr.length-1) {
			val pos = if(last == -1) 0 else last + 1
			for (i <- pos until length) {
				arr(i) = TftpServer.dataPacket((firstBlk + i).toShort, list.remove(0))
				ctr += 1
			}
		}
		ctr
	}
	
	def foreach(value: ByteBuffer => Unit): Unit = {
		arr.foreach(value)
	}
	
	def writeAll(key:Long): Unit = {
		for (i <- acked.indices) {
			if (acked(i)) {
				socket.write(TftpServer.keyXor(key,arr(i)))
				//socket.write(arr(i).flip)
				sentTime(i) = System.currentTimeMillis
			}
		}
	}
	
	def write(blknum: Int,key:Long): Unit = {
		socket.write(TftpServer.keyXor(key,arr(blknum - firstBlk)))
		sentTime(blknum - firstBlk) = System.currentTimeMillis
	}
	
	def anyWritable: Boolean = {
		for(i <- arr.indices) {
			if(sentTime(i) ==0 && arr(i)!=null)
				return true
		}
		false
	}
	
//	def getWritable: IndexedSeq[ByteBuffer] = {
//		for (i <- acked.indices) yield {
//			if (acked(i) == 0)
//				arr(i)
//		}.asInstanceOf
//	}
	def getWritable:IndexedSeq[Int] = {
		acked.indices.filter(p => !acked(p) && sentTime(p) == 0 && arr(p)!=null).map(_ + firstBlk)
//		for(i <- acked.indices) yield {
//			if(acked(i) == 0 && arr(i)!= null)
//				i
//		}.asInstanceOf
	}
	
	def inWindow(num: Int): Boolean = {
		if (num >= firstBlk && num <= firstBlk + arr.length)
			true
		else
			false
	}
	
	def slide: IndexedSeq[ByteBuffer] = {
		var stop = false
		var last = -1
		for (i <- acked.indices) {
			if (acked(i) && !stop) {
				last = i
			}
			else
				stop = true
		}
		if (last != -1) {
			return for (i <- 0 to last) yield removeFirst
		}
		null
	}
	
	def anyRetrans: Boolean = {
		val time = System.currentTimeMillis
		for (a <- sentTime.indices) {
			if (arr(a) != null && time - sentTime(a) > stdWait * waitMult(a))
				return true
		}
		return false
	}
	
	def getRetrans: IndexedSeq[Int] = {
		val time = System.currentTimeMillis
		val seq = sentTime.toIndexedSeq
		sentTime.indices.filter(p => arr(p) != null && time - sentTime(p) > stdWait * waitMult(seq.indexOf(sentTime(p))))
		//sentTime.filter(p => time - p > stdWait * waitMult(seq.indexOf(p))).toIndexedSeq.//.asInstanceOf//[IndexedSeq[Int]]
//		for (a <- sentTime.indices) yield {
//			if (time - sentTime(a) > stdWait * waitMult(a))
//				a
//			else
//				None
//		}.asInstanceOf
	}
	
	def doRetransmit(key:Long): Unit = {
		val toRetrans = getRetrans
		toRetrans.foreach(p => write(p + firstBlk,key))
		//toRetrans.foreach(p => write(p + firstBlk))
		toRetrans.foreach(p => waitMult(p) *= 2)
	}
}