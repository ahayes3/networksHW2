import java.io.{File, RandomAccessFile}
import java.net.{InetSocketAddress, SocketAddress, SocketOption, StandardSocketOptions}
import java.nio.{BufferUnderflowException, ByteBuffer}
import java.nio.channels.{DatagramChannel, FileChannel, SelectionKey, Selector}
import java.nio.file.Path

import TftpClient.keyXor

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

object TftpServer {
	
	def main(args: Array[String]): Unit = {
		
		//todo remove this
		val file = new File("potato.jpeg")
		file.delete
		//
		
		val drop: Boolean = if (args.contains("drop")) true
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
					socket.disconnect()
				}
			}
			println("END")
		}
		
		
	}
	
	def getString(buff: ByteBuffer): String = {
		var str = ""
		var a = None: Option[Byte]
		do {
			a = Some(buff.get)
			if (a.get != 0.toByte)
				str += a.get.toChar
			
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
		var blksize = 512
		var tsize = -1
		var timeout = .5
		var opc: Option[Short] = None
		var filename: Option[String] = None
		
		do {
			buff.clear
			socket.read(buff)
			buff.flip()
			try {
				val pkt = keyXor(key, buff)
				opc = Some(pkt.getShort)
				filename = Some(getString(pkt))
				var opts = new Array[String](0)
				var vals = new Array[String](0)
				
				while (pkt.position() != pkt.limit()) {
					opts = opts :+ getString(pkt)
					vals = vals :+ getString(pkt)
				}
				for (i <- opts.indices) {
					options.put(opts(i), vals(i))
				}
				valid = opts.length == vals.length
				if (valid)
					socket.write(keyXor(key, oack(options)))
				buff.clear
				
				println("here")
				
				blksize = options.getOrElse("blksize", "512").toInt
				timeout = (options.getOrElse("timeout", "1").toFloat * 1000).toInt
				tsize = options.getOrElse("tsize", "-1").toInt
				
				
			}
			catch {
				case e: BufferUnderflowException => println("Undeflow")
			}
		}
		while (!valid)
		
		if (opc.get == 1)
			rreq(socket, filename.get, blksize, timeout.toInt, tsize, key, drop)
		else if (opc.get == 2)
			wreq(socket, filename.get, blksize, timeout.toInt, tsize, key, drop)
	}
	
	//Answering a write request
	def wreq(socket: DatagramChannel, filename: String, blksize: Int, timeout: Int, tsize: Int, key: Long, drop: Boolean): Unit = { //TODO test more and repurpose
		val file = new RandomAccessFile(filename, "rw").getChannel
		val timelimit = timeout
		val recWindow = new RecWindow(5, socket)
		val buff = ByteBuffer.allocate(blksize + 4)
		var done = false
		var bytesWritten:Long = 0
		while (!done) {
			buff.clear()
			val time = System.currentTimeMillis
			while (buff.position() != buff.limit() && System.currentTimeMillis - time < timelimit) {
				socket.read(buff)
			}
			if(drop && Random.nextInt(99)==0)
				buff.clear()
			if (buff.position() == buff.limit()) {
				println("packet read")
				val packet = keyXor(key, buff)
				val opcode = packet.getShort(0) //todo handle error codes
				if(opcode!= 3)
					println("error")
				val blknum = packet.getShort(2)
				if(blknum < recWindow.firstBlk || blknum < recWindow.firstBlk+recWindow.arr.length-1)
					recWindow.ack(blknum,key)
				packet.position(4)
				val data = packet.slice
				//todo possibly check blknum and opcode to be valid
				//recWindow.ack(blknum, key)
				if (!recWindow.contains(blknum))
					recWindow.recieve(blknum, data)
				
				val toWrite = recWindow.slide
				toWrite.foreach(file.write)
				
				if (recWindow.empty && data.capacity < blksize)
					done = true
				
			}
			else if(buff.position()!=0) {
				buff.flip()
				val packet = keyXor(key,buff)
				val opcode = packet.getShort(0)
				if(opcode !=3) {
					//todo handle error
					println("error")
				}
				else {
					val blknum = packet.getShort(2)
					if(blknum < recWindow.firstBlk || blknum < recWindow.firstBlk+recWindow.arr.length-1)
						recWindow.ack(blknum,key)
					packet.position(4)
					val data = packet.slice
					if (!recWindow.contains(blknum))
						recWindow.recieve(blknum, data)
					
					val toWrite = recWindow.slide
					toWrite.foreach(bytesWritten += file.write(_))
					
					if ((tsize!= -1 && bytesWritten == tsize) || recWindow.empty && data.capacity < blksize)
						done = true
				}
				println("here3")
			}
			else {
				println("here4")
			}
		}
		Thread.sleep(timelimit)
		buff.clear()
		socket.read(buff)
		file.close()
		
		socket.disconnect()
		println("here2")
	}
	
	def rreq(socket: DatagramChannel, filename: String, blksize: Int, timeout: Int, tsize: Int, key: Long, drop: Boolean): Unit = { //TODO test
		val window = new SlidingWindow(5, socket)
		val ackBuff = ByteBuffer.allocate(4)
		val file = new RandomAccessFile(filename, "r").getChannel
		var data = ArrayBuffer.from(for (i <- 0.toLong to file.size() by blksize) yield {
			val buff = ByteBuffer.allocate(blksize)
			file.read(buff)
			buff
		})
		if(data.last.capacity() == data.head.capacity()) {
			data = data :+ ByteBuffer.allocate(0)
		}
		println("Total packets: "+ data.length)
		var position = 0
		position += window.take(data)
		
		while (!window.empty || data.nonEmpty) {
			if(data.nonEmpty)
				window.take(data)
			ackBuff.clear()
			if (window.anyWritable)
				window.getWritable.foreach(p => window.write(p,key))
			if (window.anyRetrans)
				window.doRetransmit(key)
			socket.read(ackBuff)
			if(ackBuff.position()==ackBuff.limit()) {
				val ack = keyXor(key, ackBuff)
				val opcode = ack.getShort(0)
				if(opcode == 5) {
					//todo Handle error
				}
				else {
					val ackNum = ack.getShort(2)
					println(ackNum + " acked")
					window.ack(ackNum)
					window.slide
				}
			}
		}
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
		data.position(0)
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
		val keyArr = BigInt(key).toByteArray
		val buffArr = buff.array.take(buff.limit())
		val out = ByteBuffer.wrap((for (i <- buffArr.indices) yield {
			(keyArr(i % keyArr.length) ^ buffArr(i)).toByte
		}).toArray)
		out
	}
}
