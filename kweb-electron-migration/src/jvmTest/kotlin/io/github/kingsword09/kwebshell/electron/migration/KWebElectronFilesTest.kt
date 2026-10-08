package io.github.kingsword09.kwebshell.electron.migration

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KWebElectronFilesTest {
    private fun fixture(test: (Path) -> Unit) {
        val temporary = Files.createTempDirectory("migration-inputs")
        try {
            test(temporary)
        } finally {
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun assertBlocked(operation: () -> Unit) {
        val error = assertFailsWith<KWebElectronMigrationException>(block = operation)
        assertEquals(KWebElectronMigrationErrorCode.INVENTORY_BLOCKED, error.code)
        assertTrue(error.details.containsKey("path") || error.details.containsKey("root"))
    }

    @Test fun everyRegularInputIsBoundIndependentlyOfParsedExtensions() = fixture { root ->
        root.resolve("entry.ts").writeText("export {};")
        root.resolve("config.json").writeText("{\"feature\":true}")
        root.resolve("LICENSE").writeText("license")
        root.resolve("asset.bin").writeBytes(byteArrayOf(0, -1, 42))
        root.resolve("package-lock.json").writeText("lock")
        assertEquals(setOf("LICENSE", "asset.bin", "config.json", "entry.ts"), KWebElectronFiles.applicationInputs(root).keys)
        assertEquals(setOf("entry.ts"), KWebElectronFiles.sources(root).keys)
        assertEquals(setOf("package-lock.json"), KWebElectronFiles.lockfiles(root).keys)
        val before = KWebElectronFiles.digestEntries(KWebElectronFiles.applicationInputs(root))
        root.resolve("asset.bin").writeBytes(byteArrayOf(0, -1, 43))
        assertNotEquals(before, KWebElectronFiles.digestEntries(KWebElectronFiles.applicationInputs(root)))
    }

    @Test fun everyNestedLockVariantHasAnIndependentDigest() = fixture { root ->
        val lockNames = listOf("package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock", "bun.lock", "bun.lockb")
        lockNames.forEachIndexed { index, name ->
            root.resolve("packages/fixture-$index").createDirectories().resolve(name).writeBytes(byteArrayOf(index.toByte()))
        }
        assertEquals(lockNames.size, KWebElectronFiles.lockfiles(root).size)
        assertTrue(KWebElectronFiles.applicationInputs(root).isEmpty())
        lockNames.forEachIndexed { index, name ->
            val before = KWebElectronFiles.digestEntries(KWebElectronFiles.lockfiles(root))
            root.resolve("packages/fixture-$index/$name").writeBytes(byteArrayOf(index.toByte(), 0))
            assertNotEquals(before, KWebElectronFiles.digestEntries(KWebElectronFiles.lockfiles(root)))
            assertTrue(KWebElectronFiles.applicationInputs(root).isEmpty())
        }
    }

    @Test fun relocationAndCreationOrderPreserveDigestsButPathsAndBytesMatter() = fixture { temporary ->
        val first = temporary.resolve("first").createDirectories()
        val second = temporary.resolve("second").createDirectories()
        listOf("nested/a.bin", "nested/b.json").forEach { name ->
            first.resolve(name).also { it.parent.createDirectories() }.writeText("bytes\r\n")
        }
        listOf("nested/b.json", "nested/a.bin").forEach { name ->
            second.resolve(name).also { it.parent.createDirectories() }.writeText("bytes\r\n")
        }
        assertEquals(KWebElectronFiles.tree(first), KWebElectronFiles.tree(second))
        second.resolve("nested/a.bin").writeText("bytes\n")
        assertNotEquals(KWebElectronFiles.tree(first), KWebElectronFiles.tree(second))
        second.resolve("nested/a.bin").writeText("bytes\r\n")
        Files.move(second.resolve("nested/a.bin"), second.resolve("nested/c.bin"))
        assertNotEquals(KWebElectronFiles.tree(first), KWebElectronFiles.tree(second))
    }

    @Test fun excludedDirectorySubtreesAreNotTraversedOrBound() = fixture { root ->
        root.resolve("entry.ts").writeText("export {};")
        val before = KWebElectronFiles.tree(root)
        listOf("node_modules", "nested/.git", "nested/.gradle").forEach { name ->
            val excluded = root.resolve(name).createDirectories()
            excluded.resolve("package-lock.json").writeText("ignored")
            Files.createSymbolicLink(excluded.resolve("dangling"), excluded.resolve("absent"))
        }
        assertEquals(before, KWebElectronFiles.tree(root))
        assertTrue(KWebElectronFiles.lockfiles(root).isEmpty())
    }

    @Test fun linksAreRejectedRegardlessOfTargetKindOrSourceExtension() = fixture { temporary ->
        val root = temporary.resolve("application").createDirectories()
        val outside = temporary.resolve("outside").createDirectories()
        outside.resolve("secret.bin").writeText("outside")
        val targets = listOf(outside.resolve("secret.bin"), outside, outside.resolve("absent"))
        targets.forEach { target ->
            val link = root.resolve("input.unknown")
            Files.createSymbolicLink(link, target)
            try {
                assertBlocked { KWebElectronFiles.sources(root) }
                assertBlocked { KWebElectronFiles.applicationInputs(root) }
                assertBlocked { KWebElectronFiles.lockfiles(root) }
                assertBlocked { KWebElectronFiles.tree(root) }
            } finally {
                Files.delete(link)
            }
        }
        val rootLink = temporary.resolve("root-link")
        Files.createSymbolicLink(rootLink, root)
        assertBlocked { KWebElectronFiles.tree(rootLink) }
        assertBlocked { KWebElectronInventoryScanner().scan(rootLink) }
        Files.delete(rootLink)
        Files.createSymbolicLink(rootLink, temporary.resolve("absent-root"))
        assertBlocked { KWebElectronFiles.tree(rootLink) }
        assertBlocked { KWebElectronInventoryScanner().scan(rootLink) }
        Files.delete(rootLink)
        assertEquals(KWebElectronMigrationErrorCode.PARSER_UNAVAILABLE, assertFailsWith<KWebElectronMigrationException> {
            KWebElectronInventoryScanner().scan(rootLink)
        }.code)
    }

    @Test fun fileRootsAreRejectedAndMissingOrEmptyTreesHaveTheEmptyDigest() = fixture { root ->
        assertEquals(KWebElectronFiles.text(""), KWebElectronFiles.tree(root))
        assertEquals(KWebElectronFiles.tree(root), KWebElectronFiles.tree(root.resolve("absent")))
        root.resolve("file").writeText("bytes")
        assertBlocked { KWebElectronFiles.tree(root.resolve("file")) }
    }

    @Test fun ambiguousPathFramingIsRejectedRatherThanNormalized() = fixture { root ->
        if (System.getProperty("os.name").startsWith("Windows")) {
            assertFailsWith<InvalidPathException> { root.resolve("unsafe\nname.bin") }
        } else {
            root.resolve("unsafe\nname.bin").writeText("bytes")
            assertBlocked { KWebElectronFiles.tree(root) }
            Files.delete(root.resolve("unsafe\nname.bin"))
            root.resolve("unsafe\rdirectory").createDirectories()
            assertBlocked { KWebElectronFiles.tree(root) }
        }
    }

    @Test fun filesystemReadFailureIsTypedAndCannotReturnPartialEntries() = fixture { root ->
        root.resolve("entry.ts").writeText("export {};")
        val error = assertFailsWith<KWebElectronMigrationException> {
            KWebElectronFiles.entries(root) { file ->
                Files.delete(file)
                true
            }
        }
        assertEquals(KWebElectronMigrationErrorCode.INVENTORY_BLOCKED, error.code)
        assertTrue(error.cause is IOException)
        assertEquals(root.toAbsolutePath().normalize().toString(), error.details["root"])
    }

    @Test fun fifoIsRejectedBeforeReadingOnPosixTargets() = fixture { root ->
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            val process = ProcessBuilder("mkfifo", root.resolve("input.bin").toString()).redirectErrorStream(true).start()
            val transcript = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), transcript)
            assertBlocked { KWebElectronFiles.tree(root) }
        } else {
            assertTrue(System.getProperty("os.name").startsWith("Windows"), "Only the declared Windows target has no POSIX FIFO contract.")
        }
    }
}
