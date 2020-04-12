import java.net.{InetAddress, InetSocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

import scala.collection.mutable
import scala.util.Random

object TftpClient2 {
	
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
		else 1
		
		val options = mutable.HashMap[String, String]()
		if (blksize != 512) options.put("blksize", blksize.toString)
		if (timeout != 1) options.put("timeout", timeout.toString)
		if (tsize != -1) options.put("tsize", tsize.toString)
		
		val socket = DatagramChannel.open
		socket.bind(new InetSocketAddress(port))
		socket.configureBlocking(false)
		socket.connect(new InetSocketAddress(serverAddress, serverPort))
		
		key = Some(keyExchange(socket))
		
		
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
