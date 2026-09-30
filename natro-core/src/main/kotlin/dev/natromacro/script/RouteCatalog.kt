package dev.natromacro.script

class RouteCatalog(private val programs: Map<String, Program>) {
    fun require(path: String): Program = programs[path.lowercase()]
        ?: throw IllegalArgumentException("missing route $path")

    fun names(): Set<String> = programs.keys

    companion object {
        fun load(loader: ClassLoader = RouteCatalog::class.java.classLoader): RouteCatalog {
            val index = loader.getResourceAsStream("natro/index.txt")?.bufferedReader()?.readLines()
                ?: throw IllegalStateException("natro/index.txt is missing")
            val parser = RouteParser()
            val programs = LinkedHashMap<String, Program>()
            for (line in index) {
                val path = line.trim()
                if (path.isEmpty()) continue
                val source = loader.getResourceAsStream("natro/$path")?.bufferedReader()?.readText()
                    ?: throw IllegalStateException("missing natro/$path")
                val name = path.removePrefix("paths/").removePrefix("patterns/").removeSuffix(".ahk")
                programs[name.lowercase()] = parser.parse(source, name)
            }
            return RouteCatalog(programs)
        }
    }
}
