package dev.sophi.core.permissions

import dev.sophi.core.tools.BashTool
import dev.sophi.core.tools.DeleteFileTool
import dev.sophi.core.tools.EditTool
import dev.sophi.core.tools.FetchUrlTool
import dev.sophi.core.tools.FileReadTool
import dev.sophi.core.tools.FileWriteTool
import dev.sophi.core.tools.GlobTool
import dev.sophi.core.tools.GrepTool
import dev.sophi.core.tools.GetCurrentDateTimeTool
import dev.sophi.core.tools.MoveFileTool
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class ToolSubjectsTest : FunSpec({
    val root: Path = Files.createTempDirectory("subjects").toRealPath()
    val home: Path = Files.createTempDirectory("home").toRealPath()
    fun texts(s: List<PermissionSubject>?) = s?.map { it.text }

    context("bash") {
        val bash = BashTool(root)
        test("splits into parts") {
            texts(bash.permissionSubjects("""{"command":"git status && rm x"}""")) shouldBe listOf("git status", "rm x")
        }
        test("redirecting part is not allowable") {
            bash.permissionSubjects("""{"command":"echo hi > f"}""")!!.single().allowable shouldBe false
        }
        test("unparseable command is null") {
            bash.permissionSubjects("""{"command":"echo \"x"}""") shouldBe null
        }
        test("bad json is null") { bash.permissionSubjects("{bad") shouldBe null }
        test("nothing to match is null, not empty") {
            bash.permissionSubjects("""{"command":""}""") shouldBe null
            bash.permissionSubjects("""{"command":"FOO=1"}""") shouldBe null
        }
    }

    context("path tools") {
        val write = FileWriteTool(root)
        test("equivalent spellings of .env give one subject") {
            for (p in listOf("./.env", "src/../.env", "$root/.env", ".env")) {
                texts(write.permissionSubjects("""{"path":"$p","content":"x"}""")) shouldBe listOf(".env")
            }
        }
        test("outside root is the absolute path") {
            texts(write.permissionSubjects("""{"path":"../x","content":"x"}""")) shouldBe
                listOf(root.parent.resolve("x").toString())
        }
        test("unparseable is null") { write.permissionSubjects("{bad") shouldBe null }
        test("read and edit") {
            texts(FileReadTool(root).permissionSubjects("""{"path":"a/./b.txt"}""")) shouldBe listOf("a/b.txt")
            texts(EditTool(root).permissionSubjects("""{"path":"a.txt","old_string":"a","new_string":"b"}""")) shouldBe listOf("a.txt")
        }
        test("un-normalized root still gives relative") {
            val odd = root.resolve("sub/..")
            texts(FileReadTool(odd).permissionSubjects("""{"path":"x"}""")) shouldBe listOf("x")
        }
        test("glob and grep default to the root") {
            texts(GlobTool(root).permissionSubjects("""{"pattern":"*"}""")) shouldBe listOf(".")
            texts(GrepTool(root).permissionSubjects("""{"pattern":"a","path":"src"}""")) shouldBe listOf("src")
        }
        test("delete_file expands ~") {
            texts(DeleteFileTool(root, home = home).permissionSubjects("""{"path":"~/x"}""")) shouldBe
                listOf(home.resolve("x").toString())
        }
        test("move_file has two subjects") {
            texts(MoveFileTool(root, home).permissionSubjects("""{"from":"a","to":"~/b"}""")) shouldBe
                listOf("a", home.resolve("b").toString())
        }
    }

    context("fetch_url") {
        fun url(u: String) = texts(FetchUrlTool().permissionSubjects("""{"url":"$u"}"""))
        test("normalizes scheme, host, userinfo and default port") {
            url("HTTPS://EVIL.com:443/x") shouldBe listOf("https://evil.com/x")
            url("https://user@evil.com/x") shouldBe listOf("https://evil.com/x")
            url("http://a.com:80/Path?Q=1") shouldBe listOf("http://a.com/Path?Q=1")
        }
        test("keeps a non-default port") { url("http://a.com:8080/") shouldBe listOf("http://a.com:8080/") }
        test("unparseable is null") {
            url("not a url") shouldBe null
            FetchUrlTool().permissionSubjects("{bad") shouldBe null
        }
    }

    context("real paths") {
        val write = FileWriteTool(root)
        fun subject(p: String) = texts(write.permissionSubjects("""{"path":"$p","content":"x"}"""))
        test("a symlink to .env gives .env") {
            Files.writeString(root.resolve(".env"), "s")
            Files.createSymbolicLink(root.resolve("link"), root.resolve(".env"))
            subject("link") shouldBe listOf(".env")
        }
        test("a new file under a symlinked dir resolves through the link") {
            Files.createDirectories(root.resolve("secrets"))
            Files.createSymbolicLink(root.resolve("sl"), root.resolve("secrets"))
            subject("sl/new.txt") shouldBe listOf("secrets/new.txt")
        }
        test("case-insensitive filesystem: .ENV is .env") {
            Files.writeString(root.resolve("ci.txt"), "x")
            if (Files.exists(root.resolve("CI.TXT"))) {
                Files.writeString(root.resolve(".env"), "s")
                subject(".ENV") shouldBe listOf(".env")
            }
        }
        test("root given through a symlink still gives relative subjects") {
            val viaLink = Files.createTempDirectory("l").resolve("r").also { Files.createSymbolicLink(it, root) }
            texts(FileReadTool(viaLink).permissionSubjects("""{"path":"x"}""")) shouldBe listOf("x")
        }
    }

    test("a tool that does not override has no subjects") {
        GetCurrentDateTimeTool().permissionSubjects("{}") shouldBe emptyList()
    }
})
