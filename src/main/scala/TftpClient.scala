import java.io.{File, RandomAccessFile}
import java.net.{Inet6Address, InetAddress, InetSocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.{DatagramChannel, FileChannel}
import java.nio.file.{Path, StandardOpenOption}

import TftpServer.keyXor

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

object TftpClient {
	
	def main(args: Array[String]): Unit = {
		val port = args(0).toInt
		val serverAddress = InetAddress.getByName(args(1))
		val serverPort = args(2).toInt
		
		val v6 = if (args.contains("-v6")) true
		else false
		
		
		val drop = if (args.contains("-drop")) true
		else false
		
		val readWrite = if (args.contains("read")) 1
		else if (args.contains("write")) 2
		else -1
		
		val filename = args(4)
		var key = None: Option[Long]
		
		val blksize = if (args.indexOf("blksize") != -1 && args(args.indexOf("blksize") + 1).toInt <= 65464 && args(args.indexOf("blksize") + 1).toInt >= 8) args(args.indexOf("blksize") + 1).toInt
		else 512
		val timeout = if (args.indexOf("timeout") != -1 && args(args.indexOf("timeout") + 1).toInt > 0 && args(args.indexOf("timeout") + 1).toInt <= 255) args(args.indexOf("timeout") + 1).toInt
		else -1
		val tsize = if (args.indexOf("tsize") != -1 && args(args.indexOf("tsize") + 1).toInt > 0) args(args.indexOf("tsize") + 1).toInt
		else -1
		
		val options = mutable.HashMap[String, String]()
		if (blksize != 512) options.put("blksize", blksize.toString)
		if (timeout != -1) options.put("timeout", timeout.toString)
		if (tsize != -1) options.put("tsize", tsize.toString)
		
		val socket = DatagramChannel.open()
		socket.bind(new InetSocketAddress(port))
		socket.configureBlocking(false)
		
		socket.connect(new InetSocketAddress(serverAddress, serverPort))
		if (socket.isConnected)
			key = Some(keyExchange(socket))
		else
			println("ERROR WITH CONNECTION")
		
		createConnection(socket, readWrite, filename, options, key.get, blksize, timeout, tsize, drop)
		socket.close()
	}
	
	def keyExchange(socket: DatagramChannel): Long = {
		val buff = ByteBuffer.allocate(4)
		val key1 = Random.nextInt
		var key2: Option[Int] = None
		
		var acked = false
		while (!acked) { //Sends key to server
			buff.putInt(key1)
			buff.flip
			socket.write(buff)
			buff.clear
			acked = recKeyAck(socket, buff)
		}
		
		var full = false
		while (!full) {
			buff.clear()
			full = receiveKey(socket, buff)
			key2 = Some(buff.getInt(0))
			buff.clear()
			sendKeyAck(socket, buff, full)
		}
		(key1.toLong << 32) + key2.get
		
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
	
	def createConnection(socket: DatagramChannel, readWrite: Int, filename: String, options: mutable.HashMap[String, String], key: Long, blksize: Int, timeout: Long, tsize: Int, drop: Boolean): Unit = {
		////////TODO rewrite create connection and accept connection
		val req: ByteBuffer = if (readWrite == 1) readReq(filename, options)
		else if (readWrite == 2) writeReq(filename, options)
		else throw new InvalidArguementException("Readwrite = " + readWrite)
		val buff = ByteBuffer.allocate(req.capacity() - filename.length() - 1)
		
		var reps = 0
		do {
			Thread.sleep(reps * 250)
			val toWrite = keyXor(key, req)
			socket.write(toWrite)
			reps += 1
		}
		while (!recOack(socket, buff, options, key)) //todo might need to fix recOack
		
		println("here")
		
		if (readWrite == 1)
			rreq(socket, blksize, timeout, tsize, filename, key, drop)
		else if (readWrite == 2)
			wreq(socket, blksize, timeout, tsize, filename, key, drop)
	}
	
	def rreq(socket: DatagramChannel, blksize: Int, timeout: Long, tsize: Int, filename: String, key: Long, drop: Boolean): Unit = { //todo test a lot
		val file = new RandomAccessFile(filename, "rw").getChannel
		val timelimit = if(timeout == -1) 500 else timeout*1000
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
	
	def wreq(socket: DatagramChannel, blksize: Int, timeout: Long, tsize: Int, filename: String, key: Long, drop: Boolean): Unit = { //todo convert from server write
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
		println("here2")
	}
	
	def recOack(socket: DatagramChannel, buff: ByteBuffer, options: mutable.HashMap[String, String], key: Long): Boolean = {
		buff.clear()
		
		val keyset = options.keySet
		val valset = options.valuesIterator.toArray.toSet
		val time = System.currentTimeMillis
		while (buff.position() < buff.capacity() && System.currentTimeMillis() - time < 500) {
			socket.read(buff)
		}
		val packet = keyXor(key, buff)
		val opcode = packet.getShort()
		if(opcode != 6)
			return false
		var opts = new Array[String](0)
		var vals = new Array[String](0)
		while (packet.position() < packet.limit()) {
			opts = opts :+ getString(buff)
			vals = vals :+ getString(buff)
		}
		if (keyset == opts.toSet && valset == vals.toSet)
			true
		else
			false
	}
	
	def sendKeyAck(socket: DatagramChannel, buff: ByteBuffer, good: Boolean): Unit = {
		if (good) buff.putInt(123)
		else buff.putInt(321)
		socket.write(buff.flip)
	}
	
	def recKeyAck(socket: DatagramChannel, buff: ByteBuffer): Boolean = {
		val time = System.currentTimeMillis
		while (buff.position() < buff.capacity() && System.currentTimeMillis() - time < 500) {
			socket.read(buff)
		}
		if (buff.getInt(0) == 123)
			true
		else
			false
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
	
	def readReq(filename: String, options: mutable.HashMap[String, String]): ByteBuffer = {
		val buff = ByteBuffer.allocate(3 + options.size * 2 + filename.length)
		buff.putShort(1.toShort)
		buff.put(filename.getBytes)
		buff.put(0.toByte)
		
		options.foreach(p => {
			buff.put(p._1.getBytes)
			buff.put(0.toByte)
			buff.put(p._2.getBytes)
			buff.put(0.toByte)
		})
		
		buff
	}
	
	def writeReq(filename: String, options: mutable.HashMap[String, String]): ByteBuffer = {
		val buff = ByteBuffer.allocate(3 + options.size * 2 + filename.length)
		buff.putShort(2.toShort)
		buff.put(filename.getBytes)
		buff.put(0.toByte)
		
		options.foreach(p => {
			buff.put(p._1.getBytes)
			buff.put(0.toByte)
			buff.put(p._2.getBytes)
			buff.put(0.toByte)
		})
		
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