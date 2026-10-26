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

    test("fetch_url gives the url as written") {
        texts(FetchUrlTool().permissionSubjects("""{"url":"https://Example.com/a"}""")) shouldBe listOf("https://Example.com/a")
        FetchUrlTool().permissionSubjects("{bad") shouldBe null
    }

    test("a tool that does not override has no subjects") {
        GetCurrentDateTimeTool().permissionSubjects("{}") shouldBe emptyList()
    }
})
