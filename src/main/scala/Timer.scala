import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class Timer(millis: Long) {
	def future: Future[Unit] = Future {Thread.sleep(millis)}
	
	def completed: Boolean = {
		future.isCompleted
	}
}
