import java.io.File
import java.net.URLClassLoader

fun main() {
    val file = File("app/build.gradle.kts")
    println(file.exists())
}
