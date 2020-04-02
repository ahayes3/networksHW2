import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

class SlidingWindow(length: Int) {
	var arr = new Array[ByteBuffer](length)
	var acked = new Array[Short](length)
	var firstBlk: Short = 0
	
	def apply(i: Int): ByteBuffer = {
		arr(i - firstBlk)
	}
	
	def position: Int = {
		val op = arr.indexOf(null)
		if (op == -1) 4
		else op
	}
	
	def removeFirst: ByteBuffer = {
		val a = arr.head
		firstBlk += 1
		arr = (arr.tail :+ null)
		acked = acked.tail :+ 0
		a
	}
	
	def ack(blkNum: Int): Unit = {
		acked(blkNum - firstBlk) = 2
	}
	
	def isAcked(blkNum: Int): Boolean = {
		if (blkNum - firstBlk < arr.length)
			acked(blkNum - firstBlk) == 2
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
	
	def take(list: IndexedSeq[ByteBuffer]): Int = {
		var num = 0
		var myList = list
		while (!full) {
			arr(position) = list.head
			myList = myList.tail
			num += 1
		}
		num
	}
	
	def foreach(value: ByteBuffer => Unit): Unit = {
		arr.foreach(value)
	}
	
	def writeAll(socket: DatagramChannel): Unit = {
		for (i <- acked.indices) {
			if (acked(i) == 0)
				socket.write(arr(i))
		}
	}
	
	def anyWritable:Boolean = {
		for(i <- acked){
			if(i==0)
				return true
		}
		false
	}
	
	def getWritable:IndexedSeq[ByteBuffer] = {
		for(i <- acked.indices) yield {
			if(acked(i)==0)
				arr(i)
		}.asInstanceOf
	}
	
	def inWindow(num:Int):Boolean = {
		if(num >=firstBlk && num <= firstBlk + arr.length)
			true
		else
		 false
	}
	def slide:IndexedSeq[ByteBuffer] = {
		var stop = false
		var last = -1
		for(i <- acked.indices) {
			if(acked(i) == 2 && !stop) {
				last = i
			}
			else  if(acked(i) != 2)
				stop = true
		}
		if(last != -1) {
			return for(i <- 0 until last+1) yield removeFirst
		}
		null
	}
}