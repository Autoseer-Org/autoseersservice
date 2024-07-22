package example.com

import java.io.File

object EnvLoader {
    fun arrayOfEnvs(envFilePath: String): ArrayList<Pair<String, String>> {
        val envFile = File(envFilePath)
        var arrayListOfEnv = ArrayList<Pair<String, String>>()
        if (envFile.exists()) {
            envFile.forEachLine { line ->
                val trimmedLine = line.trim()
                if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                    val keyValue = trimmedLine.split("=")
                    if (keyValue.size == 2) {
                        arrayListOfEnv.add(Pair(keyValue[0], keyValue[1]))
                    }
                }
            }
        }
        return arrayListOfEnv
    }
}