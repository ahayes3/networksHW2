import java.nio.ByteBuffer

class Node(buff: ByteBuffer, n: Node, bn: Int) {
	private val buffer = buff
	private var next = n
	private var blockNum = bn
	
	def this(buff: ByteBuffer, bn: Int) {
		this(buff, null, bn)
	}
	
	def add(buff: ByteBuffer, bn: Int, ttl: Int, ttlMax: Int): Unit = {
		if (next != null && ttl < ttlMax)
			next.add(buff, bn, ttl + 1, ttlMax)
		else if (ttl < ttlMax)
			next = new Node(buff, bn)
		else throw new IndexOutOfBoundsException("Sliding window full")
	}
	
	def size: Int = {
		if (next != null)
			1 + next.size
		else
			1
	}
	
	def get(blockNum: Int): ByteBuffer = {
		if (this.blockNum == blockNum)
			this.buffer
		else if (next != null)
			next.get(blockNum)
		else
			null
	}
	
	def getIndex(pos: Int): ByteBuffer = {
		if (pos == 0)
			buffer
		else if (next == null)
			null
		else
			next.getIndex(pos - 1)
	}
	
	def getBuffer: ByteBuffer = {
		buffer
	}
	
	def getNext: Node = {
		next
	}
}
