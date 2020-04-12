import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.{DatagramChannel, SelectionKey, Selector}

import State._
import TftpClient.{recKeyAck, receiveKey, sendKeyAck}

import scala.collection.mutable
import scala.util.Random

class ClientMachine(socket: DatagramChannel,read:Boolean,filename:String,options:mutable.HashMap[String,String],drop:Boolean) {
	var state = IDLE
	var key: Option[Long] = None
	var buff: ByteBuffer = _
	var lastPckt: TftpPacket = _
	val selector: Selector = Selector.open
	var timeout:Int = _
	var tsize:Int = _
	var timer:Long = 0
	var timerBase:Long = _
	
	val file = new File(filename)
	if(read)
		file.createNewFile()
	
	var blksize:Int = _
	socket.register(selector,SelectionKey.OP_READ)
	def lambda: Unit = { //Output: sending a packet
		(state, lastPckt) match { //todo see if idles can be put into default
			case (_, pckt: ErrPacket) => //todo handle error
			case (IDLE, _) =>;
			case (KEYEXCHANGE, _) => key = Some(keyExchange)
			case (ESTABLISHING_CONNECTION, pckt: OackPacket) =>;//todo
			case (ESTABLISHING_CONNECTION, _) => socket.write(keyXor((if(read)RrqPacket(filename,options).toByteBuffer else WrqPacket(filename,options).toByteBuffer).flip))
			case (WAITING_OACK,pckt:OackPacket) =>;
			case (WAITING_OACK,_) =>;
			case (RESEND_REQ,_) => socket.write(keyXor((if(read)RrqPacket(filename,options).toByteBuffer else WrqPacket(filename,options).toByteBuffer).flip))
		}
	}
	//End constructor
	
	def delta: Unit = { //State change and input
		
		//drop if 1
		
		val dropnext = if(drop && Random.nextInt(100) ==0) true else false
		//TODO read incoming packet here
		readPckt(buff)
		
		(state, lastPckt) match {
			case (IDLE, _) => state = KEYEXCHANGE
			case (KEYEXCHANGE, _) => state = ESTABLISHING_CONNECTION
			case (ESTABLISHING_CONNECTION, pckt: OackPacket) => state = SENDING;
			case (ESTABLISHING_CONNECTION,_) => state = WAITING_OACK; if(buff.capacity() == 512) buff.clear else buff = ByteBuffer.allocate(512)
			case (WAITING_OACK,pckt: OackPacket) => if(!verifyOack(pckt)) state = RESEND_REQ
			case(WAITING_OACK,_) => if(timer < 500) updateTimer else state = RESEND_REQ
			case (RESEND_REQ,_) => state = WAITING_OACK
		}
		
	}
	
	def updateTimer: Unit = {
		timer = System.currentTimeMillis - timerBase
	}
	
	def startTimer:Unit = {
		timerBase = System.currentTimeMillis
	}
	
	def verifyOack(packet: OackPacket): Boolean = {
		val oackOptions = packet.options
		if(oackOptions.keySet.subsetOf(options.keySet)) {
			for(opt <- oackOptions) {
				opt._1 match {
					case "blksize" => if(opt._2.toInt > options("blksize").toInt || opt._2.toInt <=0) return false
					case "timeout" => if(opt._2.toInt != options("timeout").toInt) return false
					case "tsize" => if(read && opt._2 != 0) return false else if (opt._2.toInt > file.getUsableSpace) return false
				}
			}
			blksize = oackOptions.getOrElse("blksize","512").toInt
			timeout = (oackOptions.getOrElse("timeout",".5").toFloat*1000).toInt
			tsize = oackOptions.getOrElse("tsize","-1").toInt
			true
		}
		else
			false
	}
	
	def readPckt(buffer: ByteBuffer): Unit = {
		buffer.clear()
		socket.read(buffer)
		buffer = keyXor(buffer)
	}
	
	def keyExchange: Long = {
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
	def keyXor(buff: ByteBuffer): ByteBuffer = {
		val out = ByteBuffer.allocate(buff.capacity)
		for (i <- 0 until buff.capacity by 8) {
			out.putLong(buff.getLong(i) ^ key.get)
		}
		out
	}
}
