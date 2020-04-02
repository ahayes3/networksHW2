import java.io.RandomAccessFile
import java.net.{DatagramPacket, Inet4Address, InetSocketAddress, SocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.{DatagramChannel, FileChannel}
import java.nio.file.{OpenOption, StandardOpenOption}
import java.util

import scala.collection.mutable.ArrayBuffer
import scala.collection.{LinearSeq, mutable}
import scala.util.Random

object TftpClient {
	
	def main(args: Array[String]): Unit = {
		
		val port = args(0).toInt
		val serverAddress = args(1)
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
		
		val blksize = if (args.indexOf("blksize") != -1) args(args.indexOf("blksize") + 1).toInt
		else 512
		val timeout = if (args.indexOf("timeout") != -1) args(args.indexOf("timeout") + 1).toInt
		else 1
		val tsize = if (args.indexOf("tsize") != -1) args(args.indexOf("tsize") + 1).toInt
		else -1
		val options = mutable.HashMap[String, String]()
		if (blksize != 512) options.put("blksize", blksize.toString)
		if (timeout != 1) options.put("timeout", timeout.toString)
		if (tsize != -1) options.put("tsize", tsize.toString)
		
		val socket = DatagramChannel.open()
		socket.bind(new InetSocketAddress(port))
		socket.configureBlocking(false)
		
		
		socket.connect(new InetSocketAddress(serverAddress, serverPort))
		if (socket.isConnected)
			key = Some(keyExchange(socket))
		else
			println("ERROR WITH CONNECTION")
		
		//TODO send request
		createConnection(socket, readWrite, filename, options, key.get)
	}
	
	def keyExchange(socket: DatagramChannel): Long = {
		val buff = ByteBuffer.allocate(4)
		val key1 = Random.nextInt
		buff.putInt(key1)
		socket.write(buff)
		buff.clear
		var received = false
		while (!received) {
			val time = System.currentTimeMillis
			while (buff.position() < buff.limit() && System.currentTimeMillis - time < 500) {
				socket.read(buff)
			}
			if (buff.position() == buff.limit())
				received = true
			else {
				buff.clear
				buff.putInt(123) //123 indicates the key wasn't received
				socket.write(buff)
				buff.clear
			}
		}
		val longKey = key1.toLong << 32 + buff.getInt
		longKey
	}
	
	def createConnection(socket: DatagramChannel, readWrite: Int, filename: String, options: mutable.HashMap[String, String], key: Long): Unit = {
		val oack = ByteBuffer.allocate(10000)
		val req = if (readWrite == 1) readReq(filename, options)
		else if (readWrite == 2) writeReq(filename, options)
		else throw new InvalidArguementException("Request must be read or write")
		
		var sent = false
		while (!sent) { //TODO make it check options and update sent
			socket.write(keyXor(key, req))
			val time = System.currentTimeMillis
			var bytesRead = 0
			var lastRead = 0
			while ((bytesRead == 0 || bytesRead > lastRead) && System.currentTimeMillis - time < 500) {
				lastRead = bytesRead
				bytesRead += socket.read(oack)
			}
		}
		
		if (readWrite == 1)
			rreq(socket, filename, key)
		else if (readWrite == 2)
			wreq(socket, filename, key)
		
	}
	
	def rreq(socket: DatagramChannel, filename: String, key: Long): Unit = {
		val file = new RandomAccessFile(filename, "w").getChannel
		val recWindow = new SlidingWindow(5)
		
		var done = false
		while (!done) {
			//READ packet    if packet is less than blksize its last packet
			val time = System.currentTimeMillis
			
			while () {
				socket
			}
			//send ack
			
		}
	}
	
	def wreq(socket: DatagramChannel, filename: String, key: Long): Unit = {
		val maxWindowSize = 5
		val window = ArrayBuffer
		
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
		val buff = ByteBuffer.allocate(4 + filename.length)
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
		val buff = ByteBuffer.allocate(4 + filename.length)
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