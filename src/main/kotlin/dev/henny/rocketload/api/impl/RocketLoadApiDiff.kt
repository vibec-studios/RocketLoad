package dev.henny.rocketload.api.impl

import dev.henny.rocketload.api.PackArchive
import dev.henny.rocketload.api.PackDiff
import java.security.MessageDigest

object RocketLoadApiDiff {

    fun diff(before: PackArchive, after: PackArchive): PackDiff {
        val beforePaths = before.entries().map { it.path() }.toList().toSortedSet()
        val afterPaths = after.entries().map { it.path() }.toList().toSortedSet()
        val added = afterPaths.filter { it !in beforePaths }
        val removed = beforePaths.filter { it !in afterPaths }
        val changed = ArrayList<String>()
        val unchanged = ArrayList<String>()
        for (path in afterPaths) {
            if (path !in beforePaths) continue
            val a = before.entry(path).get()
            val b = after.entry(path).get()
            val same = a.size() == b.size() && sha(a.read()).contentEquals(sha(b.read()))
            if (same) unchanged += path else changed += path
        }
        return PackDiff(added, removed, changed, unchanged)
    }

    private fun sha(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
