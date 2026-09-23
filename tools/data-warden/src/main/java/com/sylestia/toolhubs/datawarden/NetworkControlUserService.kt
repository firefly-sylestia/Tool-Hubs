package com.sylestia.toolhubs.datawarden

import kotlin.system.exitProcess

class NetworkControlUserService : INetworkControlService.Stub() {
    private fun run(args: Array<String>): String {
        return try {
            val p = ProcessBuilder(*args).redirectErrorStream(true).start()
            val output = p.inputStream.bufferedReader().readText()
            "exit=" + p.waitFor() + "\n" + output.trim()
        } catch (e: Throwable) {
            "error=" + e.javaClass.simpleName + ":" + (e.message ?: "")
        }
    }

    override fun setChain3(enabled: Boolean) =
        run(arrayOf("cmd", "connectivity", "set-chain3-enabled", enabled.toString()))

    override fun setPackageNetworking(enabled: Boolean, packageName: String): String {
        val safe = packageName.replace(Regex("[^A-Za-z0-9._-]"), "")
        if (safe != packageName || safe.isBlank()) return "error=invalid_package"
        return run(arrayOf("cmd", "connectivity", "set-package-networking-enabled", enabled.toString(), safe))
    }

    override fun destroy() { exitProcess(0) }
}
