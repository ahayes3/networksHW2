import java.net.{InetSocketAddress, SocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.{DatagramChannel, SelectionKey, Selector}

import scala.collection.mutable
import scala.util.Random

object TftpServer {
	
	def main(args:Array[String]): Unit = {
		val socket = DatagramChannel.open
		socket.bind(new InetSocketAddress(args(0).toInt))
		val selector = Selector.open
		socket.register(selector,SelectionKey.OP_CONNECT)
		var key = -1L
		while(true) {
			selector.select
			val keys = selector.selectedKeys
			val iter=keys.iterator
			while(iter.hasNext) {
				val k = iter.next
				if(k.isConnectable) {
					key = keyExchange(socket)
				}
			}
		}
		
		
		
	}
	def send(socket:DatagramChannel): Unit = {
	
	}
	def receive(socket:DatagramChannel): Unit = {
	
	}
	def getString(buff:ByteBuffer):String = {
		var str = ""
		var a:Char = null
		do {
			a = buff.getChar
			if(a!= '\0')
				str += a
			
		}while(a != '\0')
		str
	}
	def keyExchange(socket:DatagramChannel):Long = {
		val buff = ByteBuffer.allocate(4)
		var received = false
		while(!received) {
			val time = System.currentTimeMillis
			while(buff.position < buff.limit && System.currentTimeMillis - time < 500) {
				socket.read(buff)
			}
			if(buff.position == buff.limit)
				received = true
			else {
				buff.clear
				buff.putInt(123) //123 indicates the key wasn't received
				socket.write(buff)
				buff.clear
			}
		}
		val key1 = buff.getInt
		val key2 = Random.nextInt
		buff.clear
		buff.putInt(key2)
		socket.write(buff)
		val longKey = (key1.toLong << 32) + key2
		longKey
	}
	def acceptConnection(socket:DatagramChannel): Unit = {
		val requestBuff = ByteBuffer.allocate(512)
		var host:SocketAddress = null
		
		val sTime = System.currentTimeMillis
		while(requestBuff.position < requestBuff.limit && System.currentTimeMillis - sTime <1000) {
			if(host == null) {
				host = socket.receive(requestBuff)
				socket.connect(host)
			}
			else {
				socket.read(requestBuff)
			}
		}
		
		val opcode = requestBuff.getInt
		val filename = getString(requestBuff)
		val mode = getString(requestBuff)
		
		val options = mutable.HashMap[String,String]()
		while(requestBuff.position < requestBuff.limit) {
			options.put(getString(requestBuff).toLowerCase,getString(requestBuff).toLowerCase)
		}
		
		val blksize = if (options.contains("blksize") && options("blksize").toInt < 65536 && options("blksize").toInt >0) options("blksize").toInt else 512
		val timeout = if (options.contains("timeout") && options("timeout").toInt<=255 && options("timeout").toInt>0) options("timeout").toInt else 1
		var tsize = if(options.contains("tsize")) options("tsize").toInt else -1
		
		if(opcode == 1)
			send(socket)
		else if(opcode == 2)
			receive(socket)
	}
	def errorPacket(errorCode:Short,errorMsg:String): ByteBuffer = {
		val buff = ByteBuffer.allocate(5+errorMsg.length)
		buff.putShort(5.toShort)
		buff.putShort(errorCode)
		buff.put(errorMsg.getBytes)
		buff.put(0.toByte)
		buff
	}
	def dataPacket(blockNum:Short,data:ByteBuffer): ByteBuffer = {
		val buff = ByteBuffer.allocate(4+data.capacity)
		buff.putShort(3.toShort)
		buff.putShort(blockNum)
		buff.put(data)
		buff
	}
	def ackPacket(blockNum:Short): ByteBuffer = {
		val buff = ByteBuffer.allocate(4)
		buff.putShort(4.toShort)
		buff.putShort(blockNum)
		buff
	}
	def readReq(filename:String,mode:String): ByteBuffer = {
		val buff = ByteBuffer.allocate(4+filename.length+mode.length)
		buff.putShort(1.toShort)
		buff.put(filename.getBytes)
		buff.put(0.toByte)
		buff.put(mode.getBytes)
		buff.put(0.toByte)
		buff
	}
	def writeReq(filename:String,mode:String): ByteBuffer = {
		val buff = ByteBuffer.allocate(4+filename.length+mode.length)
		buff.putShort(2.toShort)
		buff.put(filename.getBytes)
		buff.put(0.toByte)
		buff.put(mode.getBytes)
		buff.put(0.toByte)
		buff
	}
	def oack(options:mutable.HashMap[String,String]): ByteBuffer = {
		var stringSpace = 0
		val keys = options.keys
		for(key <- keys) {
			stringSpace += key.length
		}
		val buff = ByteBuffer.allocate(2+options.keySet.size+stringSpace)
		buff.putShort(6.toShort)
		for(key <- keys) {
			buff.put(key.getBytes)
			buff.put(0.toByte)
			buff.put(options(key).getBytes)
			buff.put(0.toByte)
		}
		buff
	}
}
