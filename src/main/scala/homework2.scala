import java.net.InetSocketAddress
import java.nio.channels.{DatagramChannel, SelectionKey, Selector}

import scala.io.StdIn

object homework2 {
	
	import State._
	
	def main(args: Array[String]): Unit = {
		var state = WAITING
		var port: Int = -1
		if (args.isEmpty) {
			println("What port will this run on?")
			port = StdIn.readInt()
			
		}
		else {
			port = args(0).toInt
			
		}
		val selector = Selector.open()
		val channel = DatagramChannel.open()
		channel.register(selector, SelectionKey.OP_READ)
		channel.bind(new InetSocketAddress(port))
		channel.configureBlocking(false)
		
				
		
	}
}
