import java.io.RandomAccessFile
import java.net.{InetSocketAddress, SocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.{DatagramChannel, FileChannel, SelectionKey, Selector}

import TftpClient.keyXor

import scala.collection.mutable
import scala.util.Random

object TftpServer {
	
	def main(args: Array[String]): Unit = {
		val drop = if (Some(args(1)).get == "drop") true
		else false
		val socket = DatagramChannel.open
		socket.bind(new InetSocketAddress(args(0).toInt))
		socket.configureBlocking(false)
		val selector = Selector.open
		socket.register(selector, SelectionKey.OP_READ)
		var key = -1L
		while (true) {
			selector.select
			val keys = selector.selectedKeys
			if (!keys.isEmpty) println("Keys: " + keys)
			val iter = keys.iterator
			while (iter.hasNext) {
				val k = iter.next
				if (k.isReadable) {
					println("Readable")
					key = keyExchange(socket)
					acceptConnection(socket, key, drop)
					
					socket.disconnect
				}
			}
		}
		
		
	}
	
	def rreq(socket: DatagramChannel, filename: String, blksize: Int, timeout: Int, tsize: Int, key: Long): Unit = { //TODO test more and repurpose
		val file = new RandomAccessFile(filename, "w").getChannel
		val recWindow = new RecWindow(5, socket)
		val buff = ByteBuffer.allocate(blksize + 4)
		var done = false
		while (!done) {
			val bytesRead = socket.read(buff)
			val packet = keyXor(key, buff)
			val blknum = packet.getShort(2)
			packet.position(4)
			val data = packet.slice
			
			recWindow.ack(blknum, key)
			if (!recWindow.contains(blknum))
				recWindow.recieve(blknum, data)
			
			val toWrite = recWindow.slide
			toWrite.foreach(file.write)
			
			if (recWindow.empty && data.capacity < blksize)
				done = true
		}
	}
	
	def wreq(socket: DatagramChannel, file: String, blksize: Int, timeout: Int, tsize: Int, key: Long): Unit = { //TODO test
		
		val window = new SlidingWindow(5, socket)
		val ackBuff = ByteBuffer.allocate(4)
		val fChannel = new RandomAccessFile(file, "w").getChannel
		val data = ((for (i <- 0.toLong to fChannel.size() by blksize) yield {
			val buff = ByteBuffer.allocate(blksize)
			fChannel.read(ByteBuffer.allocate(blksize))
			buff
		}).map(p => keyXor(key, p))).asInstanceOf[mutable.ArrayBuffer[ByteBuffer]] //Turns file into an indexedSeq of bytebuffers to send and xors it with the key
		var position = 0
		position += window.take(data)
		
		while (!window.empty) {
			window.writeAll
			
			if (window.anyWritable)
				window.writeAll
			if (window.anyRetrans)
				window.doRetransmit
			socket.read(ackBuff)
			val ack = keyXor(key, ackBuff)
			val ackNum = ack.getShort(2)
			window.ack(ackNum)
			window.slide
		}
		
	}
	
	def getString(buff: ByteBuffer): String = {
		var str = ""
		var a = None: Option[Byte]
		do {
			a = Some(buff.get)
			if (a.get != 0.toByte)
				str += a
			
		}
		while (a.get != 0.toByte)
		str
	}
	
	def keyExchange(socket: DatagramChannel): Long = {
		val buff = ByteBuffer.allocate(4)
		val key2 = Random.nextInt
		var key1: Option[Int] = None
		var full = false
		while (!full) { //Receives key from client
			full = receiveKey(socket, buff)
			key1 = Some(buff.getInt(0))
			buff.clear()
			sendKeyAck(socket, buff, full)
		}
		
		var acked = false
		while (!acked) { //sends key to client
			buff.clear() //if this loop loops infinitely it means that the other side never got ack
			buff.putInt(key2)
			buff.flip()
			socket.write(buff)
			buff.clear()
			acked = recKeyAck(socket, buff)
		}
		
		(key1.get.toLong << 32) + key2
	}
	
	def sendKeyAck(socket: DatagramChannel, buff: ByteBuffer, good: Boolean): Unit = {
		if (good) buff.putInt(123)
		else buff.putInt(321)
		buff.flip()
		socket.write(buff)
		buff.clear()
	}
	
	def recKeyAck(socket: DatagramChannel, buff: ByteBuffer): Boolean = {
		val time = System.currentTimeMillis
		while (buff.position() < buff.capacity() && System.currentTimeMillis() - time < 500) {
			socket.read(buff)
		}
		if (buff.getInt(0) == 123) {
			buff.clear()
			true
		}
		else {
			buff.clear()
			false
		}
	}
	
	def receiveKey(socket: DatagramChannel, buff: ByteBuffer): Boolean = {
		val time = System.currentTimeMillis
		while (buff.position() < buff.capacity() && System.currentTimeMillis() - time < 500) {
			if (!socket.isConnected)
				socket.connect(socket.receive(buff))
			else
				socket.read(buff)
		}
		if (buff.position() == buff.capacity()) true
		else false
	}
	
	def acceptConnection(socket: DatagramChannel, key: Long, drop: Boolean): Unit = {
		val buff = ByteBuffer.allocate(512) //Read until reaching last \0
		val options = new mutable.HashMap[String, String]
		var valid = false
		do {
			socket.read(buff)
			buff.compact
			val pkt = keyXor(key, buff)
			val opc = buff.getShort
			var opts = new Array[String](0)
			var vals = new Array[String](0)
			while (buff.position() < buff.capacity()) {
				opts = opts :+ getString(buff)
				vals = vals :+ getString(buff)
			}
			for (i <- opts.indices) {
				options.put(opts(i), vals(i))
			}
			valid = opts.length == vals.length
			if (valid)
				socket.write(keyXor(key, oack(options)).flip)
			buff.clear
		}
		while (!valid)
		
		
		//TODO rewrite create connection and accept connection
		//		var requestBuff = ByteBuffer.allocate(512)
		//		var host: SocketAddress = null
		//
		//		var receiving = true
		//		while (receiving) {
		//			requestBuff.clear
		//			val sTime = System.currentTimeMillis
		//			while (requestBuff.position() < requestBuff.limit() && System.currentTimeMillis - sTime < 1000) {
		//				if (host == null) {
		//					host = socket.receive(requestBuff)
		//					socket.connect(host)
		//				}
		//				else {
		//					socket.read(requestBuff)
		//				}
		//			}
		//			if (requestBuff.getChar(requestBuff.position() - 1) == 0.toByte)
		//				receiving = false
		//		}
		//		requestBuff = keyXor(key, requestBuff)
		//
		//		val opcode = requestBuff.getInt
		//		val filename = getString(requestBuff)
		//
		//		val options = mutable.HashMap[String, String]()
		//		while (requestBuff.position() < requestBuff.limit()) {
		//			options.put(getString(requestBuff).toLowerCase, getString(requestBuff).toLowerCase)
		//		}
		//		val returnBuff = keyXor(key, oack(options))
		//		socket.write(returnBuff)
		//
		//		val blksize = if (options.contains("blksize") && options("blksize").toInt < 65536 && options("blksize").toInt > 0) options("blksize").toInt
		//		else 512
		//		val timeout = if (options.contains("timeout") && options("timeout").toInt <= 255 && options("timeout").toInt > 0) options("timeout").toInt
		//		else 1
		//		val tsize = if (options.contains("tsize")) options("tsize").toInt
		//		else -1
		//
		//		if (opcode == 1)
		//			rreq(socket, filename, blksize,timeout,tsize, key)
		//		else if (opcode == 2)
		//			wreq(socket, filename, blksize,timeout,tsize, key)
	}
	
	def errorPacket(errorCode: Short, errorMsg: String): ByteBuffer = {
		val buff = ByteBuffer.allocate(5 + errorMsg.length)
		buff.putShort(5.toShort)
		buff.putShort(errorCode)
		buff.put(errorMsg.getBytes)
		buff.put(0.toByte)
		buff
	}
	
	def dataPacket(blockNum: Short, data: ByteBuffer): ByteBuffer = {
		val buff = ByteBuffer.allocate(4 + data.capacity)
		buff.putShort(3.toShort)
		buff.putShort(blockNum)
		buff.put(data)
		buff
	}
	
	def ackPacket(blockNum: Short): ByteBuffer = {
		val buff = ByteBuffer.allocate(4)
		buff.putShort(4.toShort)
		buff.putShort(blockNum)
		buff
	}
	
	def readReq(filename: String, mode: String): ByteBuffer = {
		val buff = ByteBuffer.allocate(4 + filename.length + mode.length)
		buff.putShort(1.toShort)
		buff.put(filename.getBytes)
		buff.put(0.toByte)
		buff.put(mode.getBytes)
		buff.put(0.toByte)
		buff
	}
	
	def writeReq(filename: String, mode: String): ByteBuffer = {
		val buff = ByteBuffer.allocate(4 + filename.length + mode.length)
		buff.putShort(2.toShort)
		buff.put(filename.getBytes)
		buff.put(0.toByte)
		buff.put(mode.getBytes)
		buff.put(0.toByte)
		buff
	}
	
	def oack(options: mutable.HashMap[String, String]): ByteBuffer = {
		var stringSpace = 0
		val keys = options.keys
		for (key <- keys) {
			stringSpace += key.length
		}
		val buff = ByteBuffer.allocate(2 + options.keySet.size + stringSpace)
		buff.putShort(6.toShort)
		for (key <- keys) {
			buff.put(key.getBytes)
			buff.put(0.toByte)
			buff.put(options(key).getBytes)
			buff.put(0.toByte)
		}
		buff
	}
	
	def keyXor(key: Long, buff: ByteBuffer): ByteBuffer = {
		val out = ByteBuffer.allocate(buff.capacity)
		for (i <- 0 until buff.capacity by 8) {
			out.putLong(buff.getLong(i) ^ key)
		}
		out
	}
}
