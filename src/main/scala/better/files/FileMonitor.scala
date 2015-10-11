package better.files

import java.nio.file._

/**
 * A thread that can monitor a file (or a directory)
 * This class's onDelete, onCreate, onModify etc are available to be overridden
 *
 * @param file
 * @param maxDepth
 */
abstract class FileMonitor(file: File, maxDepth: Int) extends Thread {

  def this(file: File, recursive: Boolean = true) = this(file, if (recursive) Int.MaxValue else 0)

  setDaemon(true)
  setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler {
    override def uncaughtException(thread: Thread, exception: Throwable) = onException(exception)
  })

  protected[this] val service = file.newWatchService

  override def run() = Iterator.continually(service.take()) foreach process

  override def interrupt() = {
    service.close()
    super.interrupt()
  }

  override def start() = {
    watch(file, maxDepth)
    super.start()
  }

  protected[this] def process(key: WatchKey) = {
    import scala.collection.JavaConversions._
    val root = key.watchable().asInstanceOf[Path]
    key.pollEvents() foreach {
      case event: WatchEvent[Path] @unchecked =>
        val target = File(root resolve event.context())
        val react = if (file.isDirectory) {
          if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) watch(target, maxDepth)   //TODO: correct depth // auto-watch new files in a directory
          true
        } else file isSamePathAs target  // if watching non-directory, don't react to siblings
        if (react) repeat(event.count())(dispatch(event.kind(), target))
      case event => onUnknownEvent(event, root)
    }
    key.reset()
  }

  protected[this] def watch(aFile: File, depth: Int): Unit = if (aFile.isDirectory) {
    for {
      f <- aFile.walk(depth) if f.isDirectory && f.exists
    } f.path.register(service, FileMonitor.allEvents: _*)
  } else if (aFile.exists) {   // There is no way to watch a regular file; so watch its parent instead
    aFile.parent.path.register(service, FileMonitor.allEvents: _*)
  }

  def dispatch(eventType: WatchEvent.Kind[Path], file: File): Unit = eventType match {
    case StandardWatchEventKinds.ENTRY_CREATE => onCreate(file)
    case StandardWatchEventKinds.ENTRY_MODIFY => onModify(file)
    case StandardWatchEventKinds.ENTRY_DELETE => onDelete(file)
  }

  def onCreate(file: File): Unit = {}

  def onModify(file: File): Unit = {}

  def onDelete(file: File): Unit = {}

  def onUnknownEvent(event: WatchEvent[_], root: File): Unit = {}

  def onException(exception: Throwable): Unit = {}
}

object FileMonitor {
  private[FileMonitor] val allEvents = Seq(StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE)
}
